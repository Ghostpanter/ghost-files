package com.ghostpanter.files.core.space

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Cooperative cancellation + progress shared by every scan source. */
class ScanControl {
    private val cancelled = AtomicBoolean(false)
    @Volatile var current: String = ""
    fun cancel() { cancelled.set(true); onCancel.forEach { runCatching { it() } } }
    val isCancelled get() = cancelled.get()
    internal val onCancel = ConcurrentLinkedQueue<() -> Unit>()
    fun check() { if (cancelled.get()) throw ScanCancelled() }
}

class ScanCancelled : IOException("scan cancelled")

/**
 * The same storage under several names: /sdcard, /storage/self/primary, /storage/emulated/0,
 * /mnt/user/0/emulated/0 and (Root) /data/media/0. Root scans read the /data/media one (no FUSE).
 */
object PathCanon {
    private val ALIASES = listOf(
        "/sdcard" to "/data/media/0",
        "/storage/self/primary" to "/data/media/0",
        "/mnt/sdcard" to "/data/media/0",
        "/storage/emulated/legacy" to "/data/media/0",
    )
    private val EMULATED = Regex("^/(storage/emulated|mnt/user/\\d+/emulated|mnt/runtime/\\w+/emulated|mnt/pass_through/\\d+/emulated)/(\\d+)(/.*)?$")
    private val MEDIA = Regex("^/data/media/(\\d+)(/.*)?$")

    /** The single physical name of [path] (for de-duplicating scan roots). */
    fun canonical(path: String): String {
        val p = VPath.normalize(path)
        for ((alias, real) in ALIASES) if (p == alias || p.startsWith("$alias/")) return real + p.removePrefix(alias)
        EMULATED.matchEntire(p)?.let { m -> return "/data/media/" + m.groupValues[2] + m.groupValues[3] }
        return p
    }

    /** What a user (and the local file backend) calls a /data/media path. */
    fun userVisible(path: String): String {
        val m = MEDIA.matchEntire(VPath.normalize(path)) ?: return path
        return "/storage/emulated/" + m.groupValues[1] + m.groupValues[2]
    }

    fun isUserStorage(path: String) = canonical(path).let { it.startsWith("/data/media/") || it == "/data/media" }

    /** Drops roots that alias or sit inside another root (after [canonical]); keeps the first spelling's order. */
    fun dedupeRoots(roots: List<String>): List<String> {
        val canon = roots.map { canonical(it) }.distinct()
        return canon.filter { r -> canon.none { o -> o != r && VPath.isSameOrDescendant(o, r) } }
    }
}

/** How dangerous deleting a path is. */
enum class Protection { NONE, CAUTION, SYSTEM_READ_ONLY }

object SpacePolicy {
    private val SYSTEM = listOf("/system", "/vendor", "/product", "/system_ext", "/odm", "/apex", "/oem", "/odm_dlkm", "/vendor_dlkm", "/system_dlkm", "/metadata", "/bin", "/etc", "/sbin", "/lib", "/init", "/dev", "/proc", "/sys")
    private val CAUTION = listOf("/data/app", "/data/data", "/data/user", "/data/user_de", "/data/system", "/data/system_ce", "/data/system_de", "/data/misc", "/data/misc_ce", "/data/misc_de", "/data/vendor", "/data/adb", "/data/dalvik-cache", "/data/apex", "/data/property", "/cache", "/persist", "/mnt")

    fun protection(path: String): Protection {
        val p = PathCanon.canonical(path)
        if (p == "/" || p == "/data") return Protection.SYSTEM_READ_ONLY
        if (SYSTEM.any { VPath.isSameOrDescendant(it, p) } || p.startsWith("/my_")) return Protection.SYSTEM_READ_ONLY
        if (p.startsWith("/data/media")) return Protection.NONE
        if (CAUTION.any { VPath.isSameOrDescendant(it, p) }) return Protection.CAUTION
        if (p.startsWith("/data/")) return Protection.CAUTION
        if (p.startsWith("/storage/") || p.startsWith("/sdcard")) return Protection.NONE
        return Protection.CAUTION
    }

    fun readOnlyPartition(path: String) = protection(path) == Protection.SYSTEM_READ_ONLY && PathCanon.canonical(path).let { it != "/data" }
}

/**
 * Streams `find` through a shell (su / Shizuku / adb shell) and feeds a [SpaceTreeBuilder].
 * Uses `find -printf` when the toybox supports it (Android 11+), else `find -exec stat {} +`.
 * Never follows symlinks (find -P); hard links and bind-mount aliases are de-duplicated by inode.
 */
object ShellStreamScan {
    /** Field order of both formats: dev|ino|nlink|size|mtime|type|path. */
    private const val PRINTF = "%D|%i|%n|%s|%T@|%y|%p\\n"
    private const val STAT = "%d|%i|%h|%s|%Y|%f|%n"

    fun script(roots: List<String>, prune: List<String> = emptyList()): String {
        val q = roots.joinToString(" ") { VPath.shellQuote(it) }
        val pr = if (prune.isEmpty()) "" else "\\( " + prune.joinToString(" -o ") { "-path ${VPath.shellQuote(it)}" } + " \\) -prune -o "
        // Probe: older toybox lacks -printf (or some of its fields); the output must look right.
        return "if find / -maxdepth 0 -printf '$PRINTF' 2>/dev/null | grep -qE '^[0-9]+[|][0-9]+[|][0-9]+[|][0-9]+[|][0-9.]+[|]d[|]/$'; then\n" +
            "  find $q $pr-printf '$PRINTF' 2>/dev/null\n" +
            "else\n" +
            "  find $q $pr-exec stat -c '$STAT' {} + 2>/dev/null\n" +
            "fi\necho '--GHOST-SCAN-END--'\n"
    }

    class Line(val dev: Long, val ino: Long, val nlink: Int, val size: Long, val mtimeMs: Long, val dir: Boolean, val file: Boolean, val path: String)

    /** Parses one output line of either format (null for noise / unsupported types). */
    fun parse(line: String): Line? {
        val f = line.split('|', limit = 7)
        if (f.size < 7) return null
        val dev = f[0].toLongOrNull() ?: f[0].toLongOrNull(16) ?: return null
        val ino = f[1].toLongOrNull() ?: return null
        val nlink = f[2].toIntOrNull() ?: 1
        val size = f[3].toLongOrNull() ?: return null
        val mt = f[4].toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L
        val t = f[5]
        val (dir, file) = when {
            t == "d" -> true to false
            t == "f" -> false to true
            t.length == 1 -> false to false // l, c, b, p, s
            else -> {
                val mode = t.toIntOrNull(16) ?: return null
                when (mode and 0xF000) { 0x4000 -> true to false; 0x8000 -> false to true; else -> false to false }
            }
        }
        val path = f[6].let { if (it.length > 1) it.trimEnd('/') else it }
        if (path.isEmpty() || !path.startsWith("/")) return null
        return Line(dev, ino, nlink, size, mt, dir, file, path)
    }

    /**
     * Runs the scan. [mapPath] can rename paths before they enter the tree (e.g. keep a root's
     * spelling). Returns false when the stream ended without the end marker (killed / failed).
     */
    fun run(runner: ShellRunner, roots: List<String>, b: SpaceTreeBuilder, ctl: ScanControl, prune: List<String> = emptyList(), mapPath: (String) -> String = { it }): Boolean {
        val p = runner.start(script(roots, prune))
        ctl.onCancel.add { com.ghostpanter.files.core.Proc.kill(p) }
        runCatching { p.outputStream.close() }
        // stderr is drained in the background so a chatty find can't block on a full pipe.
        val finish = com.ghostpanter.files.core.Proc.afterStream(p)
        var ended = false
        try {
            p.inputStream.bufferedReader(Charsets.UTF_8).use { r ->
                val batch = ArrayList<Line>(512)
                fun flush() {
                    if (batch.isEmpty()) return
                    b.batch {
                        for (l in batch) {
                            val path = mapPath(l.path)
                            if (l.dir) addDirLocked(this, path, l)
                            else if (l.file) addFileLocked(path, l.size, l.mtimeMs, l.dev, l.ino, l.nlink)
                        }
                    }
                    ctl.current = mapPath(batch.last().path)
                    batch.clear()
                }
                while (true) {
                    if (ctl.isCancelled) throw ScanCancelled()
                    val line = r.readLine() ?: break
                    if (line == "--GHOST-SCAN-END--") { ended = true; break }
                    parse(line)?.let { batch.add(it) }
                    if (batch.size >= 512) flush()
                }
                flush()
            }
        } finally {
            finish(2000)
        }
        if (ctl.isCancelled) throw ScanCancelled()
        return ended
    }

    private fun addDirLocked(b: SpaceTreeBuilder, path: String, l: Line) {
        // Same as addDir() but we already hold the lock.
        b.addDirUnlocked(path, l.mtimeMs, l.dev, l.ino)
    }
}

/**
 * Walks a local tree with java.nio (lstat per entry: no symlink following, inode from the file key),
 * [threads] directories in parallel. With [previous] (the cached tree), a directory whose mtime is
 * unchanged reuses its cached files and only its sub-directories are revisited (incremental refresh).
 */
class LocalWalker(
    private val threads: Int = 4,
    private val isAppParentUnreadable: (String) -> Boolean = { false },
) {
    var reusedDirs = AtomicInteger(0); private set
    var listedDirs = AtomicInteger(0); private set

    fun walk(rootPath: String, b: SpaceTreeBuilder, ctl: ScanControl, previous: SpaceNode? = null, skip: (String) -> Boolean = { false }) {
        val pool = Executors.newFixedThreadPool(threads.coerceAtLeast(1)) { r -> Thread(r, "space-walk").apply { isDaemon = true } }
        val pending = AtomicInteger(0)
        val lock = Object()
        var error: Throwable? = null
        fun submit(dir: SpaceNode, path: String, prev: SpaceNode?) {
            pending.incrementAndGet()
            pool.execute {
                try {
                    if (!ctl.isCancelled) visit(dir, path, prev, b, ctl, skip) { d, p, pv -> submit(d, p, pv) }
                } catch (t: Throwable) {
                    if (t !is ScanCancelled) synchronized(lock) { if (error == null) error = t }
                } finally {
                    if (pending.decrementAndGet() == 0) synchronized(lock) { lock.notifyAll() }
                }
            }
        }
        submit(b.root, rootPath, previous)
        synchronized(lock) { while (pending.get() > 0 && !ctl.isCancelled) lock.wait(200) }
        pool.shutdownNow()
        pool.awaitTermination(2, TimeUnit.SECONDS)
        if (ctl.isCancelled) throw ScanCancelled()
        error?.let { throw it }
    }

    private fun attrs(p: Path): BasicFileAttributes? = runCatching { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }.getOrNull()

    private fun visit(dir: SpaceNode, path: String, prev: SpaceNode?, b: SpaceTreeBuilder, ctl: ScanControl, skip: (String) -> Boolean, sub: (SpaceNode, String, SpaceNode?) -> Unit) {
        ctl.current = path
        val p = Paths.get(path)
        val a = attrs(p)
        val mtime = a?.lastModifiedTime()?.toMillis() ?: 0L
        synchronized(b.tree.lock) { dir.mtime = mtime }
        // Incremental: same directory mtime ⇒ same entries. Reuse cached files, revisit only sub-dirs.
        if (prev != null && prev.mtime == mtime && mtime != 0L && prev.has(NodeFlags.COMPLETE)) {
            reusedDirs.incrementAndGet()
            val subs = ArrayList<Pair<SpaceNode, SpaceNode>>()
            synchronized(b.tree.lock) {
                prev.children?.forEach { c ->
                    when (c.kind) {
                        NodeKind.FILE -> b.addToDir(dir, c.name, c.size, c.mtime)
                        NodeKind.SMALL_FILES -> b.graft(dir, copyLeaf(c))
                        NodeKind.DIR -> { val n = b.dirChild(dir, c.name, VPath.join(path, c.name)); n.mtime = c.mtime; subs.add(n to c) }
                        else -> {}
                    }
                }
                b.markComplete(dir)
                b.tree.version++
            }
            for ((n, c) in subs) {
                val cp = VPath.join(path, c.name)
                if (!skip(cp)) sub(n, cp, c)
            }
            return
        }
        listedDirs.incrementAndGet()
        val files = ArrayList<Triple<String, Long, Long>>()
        val dirs = ArrayList<Pair<String, Long>>()
        val ok = try {
            Files.newDirectoryStream(p).use { ds: DirectoryStream<Path> ->
                for (e in ds) {
                    if (ctl.isCancelled) return
                    val ea = attrs(e) ?: continue
                    val name = e.fileName.toString()
                    when {
                        ea.isSymbolicLink -> {}
                        ea.isDirectory -> dirs.add(name to (ea.lastModifiedTime().toMillis()))
                        ea.isRegularFile -> files.add(Triple(name, ea.size(), ea.lastModifiedTime().toMillis()))
                    }
                }
            }
            true
        } catch (_: Exception) { false }
        val children = ArrayList<Pair<SpaceNode, String>>()
        synchronized(b.tree.lock) {
            if (!ok) dir.flags = dir.flags or NodeFlags.UNREADABLE
            for ((n, s, m) in files) b.addToDir(dir, n, s, m)
            for ((n, m) in dirs) {
                val cp = VPath.join(path, n)
                if (skip(cp)) continue
                val c = b.dirChild(dir, n, cp); c.mtime = m
                children.add(c to cp)
            }
            if (ok) b.markComplete(dir)
            b.tree.version++
        }
        // Android 11+: Android/data|obb list as empty for apps (not an error) — let the caller flag it.
        if (ok && files.isEmpty() && dirs.isEmpty() && isAppParentUnreadable(path)) synchronized(b.tree.lock) { dir.flags = dir.flags or NodeFlags.UNREADABLE }
        for ((c, cp) in children) sub(c, cp, prev?.child(c.name)?.takeIf { it.kind == NodeKind.DIR })
    }

    private fun copyLeaf(c: SpaceNode) = SpaceNode(c.name, c.kind).also { n ->
        n.size = c.size; n.count = c.count; n.mtime = c.mtime; n.category = c.category; n.catSizes = c.catSizes?.copyOf()
    }
}

/** Generic walker over any [VirtualFs] (SAF, network shares, ADB without find): one list() per directory. */
object VfsWalker {
    fun walk(fs: VirtualFs, rootPath: String, b: SpaceTreeBuilder, ctl: ScanControl) {
        val stack = ArrayDeque<Pair<SpaceNode, String>>()
        stack.addLast(b.root to rootPath)
        while (stack.isNotEmpty()) {
            ctl.check()
            val (dir, path) = stack.removeLast()
            ctl.current = path
            val list: List<FileEntry> = try { fs.list(path) } catch (e: Exception) {
                if (ctl.isCancelled) throw ScanCancelled()
                synchronized(b.tree.lock) { dir.flags = dir.flags or NodeFlags.UNREADABLE }; continue
            }
            val subs = ArrayList<Pair<SpaceNode, String>>()
            synchronized(b.tree.lock) {
                for (e in list) {
                    if (e.isLink) continue
                    if (e.isDirectory) {
                        val c = b.dirChild(dir, e.name, VPath.join(path, e.name)); c.mtime = e.lastModified
                        subs.add(c to VPath.join(path, e.name))
                    } else b.addToDir(dir, e.name, e.size, e.lastModified)
                }
                b.markComplete(dir)
                b.tree.version++
            }
            subs.asReversed().forEach { stack.addLast(it) }
        }
    }
}
