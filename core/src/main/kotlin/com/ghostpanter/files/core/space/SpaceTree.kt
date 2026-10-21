package com.ghostpanter.files.core.space

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Soft color class of a file (SpaceSniffer-style legend). */
enum class FileCategory {
    IMAGE, VIDEO, AUDIO, DOC, ARCHIVE, APK, OTHER;

    companion object {
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "dng", "raw", "avif", "svg", "tif", "tiff")
        private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "webm", "3gp", "ts", "m4v", "flv", "wmv", "rmvb", "m2ts", "mpg", "mpeg")
        private val AUDIO_EXT = setOf("mp3", "flac", "aac", "m4a", "ogg", "opus", "wav", "wma", "amr", "ape", "alac", "mid")
        private val DOC_EXT = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "epub", "csv", "json", "xml", "html", "log", "odt", "rtf")
        private val ARCHIVE_EXT = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "xz", "bz2", "zst", "lz4", "img", "iso", "bin", "obb", "gfenc")
        private val APK_EXT = setOf("apk", "apks", "xapk", "apkm", "aab", "odex", "vdex", "oat", "dex", "art")

        fun of(name: String): FileCategory {
            val dot = name.lastIndexOf('.')
            if (dot <= 0 || dot == name.length - 1) return OTHER
            val e = name.substring(dot + 1).lowercase()
            return when (e) {
                in IMAGE_EXT -> IMAGE
                in VIDEO_EXT -> VIDEO
                in AUDIO_EXT -> AUDIO
                in DOC_EXT -> DOC
                in ARCHIVE_EXT -> ARCHIVE
                in APK_EXT -> APK
                else -> OTHER
            }
        }
    }
}

enum class NodeKind {
    /** A real directory (drillable). */
    DIR,
    FILE,
    /** Many small files of one directory folded into one block (bounded memory on huge trees). */
    SMALL_FILES,
    /** A non-drillable total from system APIs (system, per-app data…) or a group of those. */
    AGGREGATE,
    /** Explains an area the current access tier cannot read (size may be 0 or an estimate). */
    NOTICE,
}

object NodeFlags {
    /** Package folder whose app is not installed (uninstall leftover). */
    const val RESIDUE = 1
    /** On a read-only system partition. */
    const val READ_ONLY = 2
    /** Could not be listed (permission). */
    const val UNREADABLE = 4
    /** Aggregate stands for one app (pkg set). */
    const val APP = 8
    /** Directory listing finished (incremental refresh may reuse it). */
    const val COMPLETE = 16
    /** System-API total, not a file (e.g. 「系统」). */
    const val SYSTEM = 32
    /** Free space block. */
    const val FREE = 64
}

/**
 * One block of the space tree. Directories hold the total of their subtree. Mutated only by
 * [SpaceTreeBuilder] under [SpaceTree.lock]; readers take the lock (or use a finished tree).
 */
class SpaceNode(
    var name: String,
    val kind: NodeKind,
    var parent: SpaceNode? = null,
) {
    var size: Long = 0
    /** Files in the subtree (SMALL_FILES: files folded in). */
    var count: Int = 0
    /** Epoch millis (dirs: their own mtime, SMALL_FILES: newest file). */
    var mtime: Long = 0
    var category: FileCategory = FileCategory.OTHER
    var flags: Int = 0
    /** App package for package folders / app aggregates. */
    var pkg: String? = null
    /** Extra line for aggregates ("安装包 1.2 GB · 数据 300 MB · 缓存 80 MB"). */
    var detail: String? = null
    /** SMALL_FILES: bytes per [FileCategory] (keeps type filters exact for folded files). */
    var catSizes: LongArray? = null
    var children: ArrayList<SpaceNode>? = null

    /** Scratch value of the active filter (see [SpaceFilter.apply]). */
    @Volatile var shown: Long = 0

    val isDir get() = kind == NodeKind.DIR
    val drillable get() = (kind == NodeKind.DIR || (kind == NodeKind.AGGREGATE && !children.isNullOrEmpty()))
    fun has(f: Int) = flags and f != 0

    /** Absolute path; virtual roots have an empty name and children with absolute names. */
    fun path(): String {
        val p = parent?.path() ?: return name
        if (name.startsWith("/")) return name
        if (p.isEmpty()) return "/$name"
        return if (p == "/") "/$name" else "$p/$name"
    }

    fun child(name: String): SpaceNode? = children?.firstOrNull { it.name == name }

    fun addChild(c: SpaceNode) {
        c.parent = this
        (children ?: ArrayList<SpaceNode>(4).also { children = it }).add(c)
    }

    fun depth(): Int { var d = 0; var p = parent; while (p != null) { d++; p = p.parent }; return d }

    /** Root-first chain (for breadcrumbs). */
    fun chain(): List<SpaceNode> { val out = ArrayList<SpaceNode>(); var n: SpaceNode? = this; while (n != null) { out.add(n); n = n.parent }; return out.asReversed() }

    override fun toString() = "$kind ${path()} $size"
}

/** A tree plus the lock guarding it while a scan runs. */
class SpaceTree(val root: SpaceNode) {
    val lock = Any()
    /** Bumped on every applied batch so the UI can re-snapshot. */
    @Volatile var version: Long = 0
    @Volatile var scannedAt: Long = 0

    /** Children of [n], largest first, copied under the lock (safe to lay out while the scan runs). */
    fun snapshot(n: SpaceNode, bySize: (SpaceNode) -> Long = { it.size }): List<Pair<SpaceNode, Long>> = synchronized(lock) {
        (n.children ?: return emptyList()).map { it to bySize(it) }.filter { it.second > 0 }.sortedByDescending { it.second }
    }

    /** Finds a node by absolute path (null when not in the tree). */
    fun find(path: String): SpaceNode? = synchronized(lock) { findIn(root, path) }

    companion object {
        fun findIn(root: SpaceNode, path: String): SpaceNode? {
            val rp = root.path()
            if (path == rp) return root
            // Virtual root: pick the absolute child that prefixes the path.
            if (rp.isEmpty()) {
                val c = root.children?.firstOrNull { path == it.name || path.startsWith(it.name.trimEnd('/') + "/") } ?: return null
                return findIn(c, path)
            }
            val base = if (rp == "/") "" else rp
            if (!path.startsWith("$base/")) return null
            var cur = root
            for (seg in path.substring(base.length + 1).split('/')) {
                if (seg.isEmpty()) continue
                cur = cur.child(seg) ?: return null
            }
            return cur
        }
    }
}

/**
 * Builds a [SpaceTree] from a stream of entries (any order where a directory may arrive before or
 * after its contents). Bounded memory: files smaller than [smallBytes] fold into one SMALL_FILES
 * block per directory once a directory has more than [maxSmallPerDir] of them. Hard links (same
 * dev+inode, nlink > 1) count once; a directory seen twice by inode (bind mount / alias) is skipped
 * with its whole subtree.
 */
class SpaceTreeBuilder(rootPath: String, val smallBytes: Long = 128 * 1024, val maxSmallPerDir: Int = 24) {
    val root = SpaceNode(rootPath, NodeKind.DIR)
    val tree = SpaceTree(root)
    private val dirs = HashMap<String, SpaceNode>().apply { put(rootPath, root) }
    private val smallCount = HashMap<SpaceNode, Int>()
    private val seenFiles = HashSet<Long>()
    private val seenDirs = HashSet<Long>()
    private val skipped = HashSet<String>()
    private val rootBase = rootPath.trimEnd('/')

    var files = 0L; private set
    var bytes = 0L; private set
    var dedupedBytes = 0L; private set

    private fun key(dev: Long, ino: Long) = (dev shl 40) xor ino

    private fun parentOf(p: String): String {
        val i = p.lastIndexOf('/')
        return if (i <= 0) "/" else p.substring(0, i)
    }

    private fun inside(p: String) = p == root.name || p.startsWith("$rootBase/") || rootBase.isEmpty()

    /** Directory node for [path], creating missing ancestors. Null when outside the root or skipped. */
    fun ensureDir(path: String): SpaceNode? {
        dirs[path]?.let { return it }
        if (!inside(path) || path in skipped) return null
        val parentPath = parentOf(path)
        if (parentPath in skipped) { skipped.add(path); return null }
        val parent = ensureDir(parentPath) ?: return null
        val n = SpaceNode(path.substring(path.lastIndexOf('/') + 1), NodeKind.DIR)
        parent.addChild(n)
        dirs[path] = n
        return n
    }

    fun addDir(path: String, mtime: Long, dev: Long = -1, ino: Long = -1): SpaceNode? = synchronized(tree.lock) {
        addDirUnlocked(path, mtime, dev, ino).also { tree.version++ }
    }

    /** [addDir] for callers already holding [SpaceTree.lock]. */
    fun addDirUnlocked(path: String, mtime: Long, dev: Long = -1, ino: Long = -1): SpaceNode? {
        if (path in skipped || parentOf(path) in skipped) { skipped.add(path); return null }
        if (ino > 0) {
            val k = key(dev, ino)
            if (!seenDirs.add(k) && path != root.name) { skipped.add(path); return null }
        }
        val n = ensureDir(path) ?: return null
        n.mtime = mtime
        return n
    }

    fun addFile(path: String, size: Long, mtime: Long, dev: Long = -1, ino: Long = -1, nlink: Int = 1) = synchronized(tree.lock) {
        addFileLocked(path, size, mtime, dev, ino, nlink)
        tree.version++
    }

    /** Applies many entries under one lock (shell streams parse in batches). */
    fun batch(block: SpaceTreeBuilder.() -> Unit) = synchronized(tree.lock) { block(); tree.version++ }

    fun addFileLocked(path: String, size: Long, mtime: Long, dev: Long = -1, ino: Long = -1, nlink: Int = 1) {
        val parentPath = parentOf(path)
        if (parentPath in skipped) return
        if (nlink > 1 && ino > 0 && !seenFiles.add(key(dev, ino))) { dedupedBytes += size; return }
        val dir = ensureDir(parentPath) ?: return
        addToDir(dir, path.substring(path.lastIndexOf('/') + 1), size, mtime)
    }

    /** Adds a file by name to [dir] (local walkers already hold the node). Caller holds the lock. */
    fun addToDir(dir: SpaceNode, name: String, size: Long, mtime: Long) {
        files++; bytes += size
        val cat = FileCategory.of(name)
        if (size < smallBytes) {
            val small = dir.children?.lastOrNull { it.kind == NodeKind.SMALL_FILES }
            if (small != null) { fold(small, size, mtime, cat); propagate(dir, size); return }
            val c = (smallCount[dir] ?: 0) + 1
            if (c > maxSmallPerDir) {
                smallCount.remove(dir)
                val s = SpaceNode("", NodeKind.SMALL_FILES)
                s.catSizes = LongArray(FileCategory.entries.size)
                val it = dir.children!!.iterator()
                while (it.hasNext()) {
                    val f = it.next()
                    if (f.kind == NodeKind.FILE && f.size < smallBytes) {
                        fold(s, f.size, f.mtime, f.category); it.remove()
                    }
                }
                dir.addChild(s)
                fold(s, size, mtime, cat)
                propagate(dir, size)
                return
            }
            smallCount[dir] = c
        }
        val f = SpaceNode(name, NodeKind.FILE)
        f.size = size; f.mtime = mtime; f.category = cat; f.count = 1
        dir.addChild(f)
        propagate(dir, size)
    }

    private fun fold(s: SpaceNode, size: Long, mtime: Long, cat: FileCategory) {
        s.size += size; s.count++
        if (mtime > s.mtime) s.mtime = mtime
        s.catSizes!![cat.ordinal] += size
    }

    private fun propagate(dir: SpaceNode, size: Long) {
        var n: SpaceNode? = dir
        while (n != null) { n.size += size; n.count++; n = n.parent }
    }

    /** Attaches a finished subtree (incremental refresh reuse, other scan sources). Caller holds the lock. */
    fun graft(dir: SpaceNode, sub: SpaceNode) {
        dir.addChild(sub)
        var n: SpaceNode? = dir
        while (n != null) { n.size += sub.size; n.count += sub.count; n = n.parent }
        files += sub.count; bytes += sub.size
        if (sub.kind == NodeKind.DIR) indexDirs(sub)
    }

    private fun indexDirs(n: SpaceNode) {
        if (n.kind != NodeKind.DIR) return
        dirs[n.path()] = n
        n.children?.forEach { indexDirs(it) }
    }

    /**
     * Sub-directory [name] of [parent] at [path], registered so other sources writing into the same
     * tree (Shizuku stream beside a local walk) find it instead of creating a twin. Caller holds the lock.
     */
    fun dirChild(parent: SpaceNode, name: String, path: String): SpaceNode {
        dirs[path]?.let { return it }
        val c = SpaceNode(name, NodeKind.DIR)
        parent.addChild(c)
        dirs[path] = c
        return c
    }

    fun markUnreadable(path: String) = synchronized(tree.lock) { ensureDir(path)?.let { it.flags = it.flags or NodeFlags.UNREADABLE } }

    fun markComplete(dir: SpaceNode) { dir.flags = dir.flags or NodeFlags.COMPLETE }

    fun isSkipped(path: String) = path in skipped
}

/** Filters of the analysis view. Null = any. */
data class SpaceFilter(
    val category: FileCategory? = null,
    val minBytes: Long = 0,
    /** Only files modified at/after this epoch ms. */
    val newerThan: Long = 0,
    /** Only files modified before this epoch ms. */
    val olderThan: Long = 0,
) {
    val active get() = category != null || minBytes > 0 || newerThan > 0 || olderThan > 0

    fun matches(n: SpaceNode): Boolean {
        if (n.kind != NodeKind.FILE) return false
        if (category != null && n.category != category) return false
        if (n.size < minBytes) return false
        if (newerThan > 0 && n.mtime < newerThan) return false
        if (olderThan > 0 && (n.mtime == 0L || n.mtime >= olderThan)) return false
        return true
    }

    /** Bytes of [n] that pass (folded small files: by category only, and only when no size/time filter). */
    private fun own(n: SpaceNode): Long = when (n.kind) {
        NodeKind.FILE -> if (matches(n)) n.size else 0
        NodeKind.SMALL_FILES -> when {
            minBytes > 0 || newerThan > 0 || olderThan > 0 -> 0
            category != null -> n.catSizes?.get(category.ordinal) ?: 0
            else -> n.size
        }
        NodeKind.AGGREGATE, NodeKind.NOTICE -> if (active) 0 else n.size
        NodeKind.DIR -> 0
    }

    /** Writes the filtered size of every node of [root] into [SpaceNode.shown]; returns the root's. */
    fun apply(root: SpaceNode): Long {
        val ch = root.children
        if (ch.isNullOrEmpty() || root.kind == NodeKind.SMALL_FILES) { root.shown = if (active) own(root) else root.size; return root.shown }
        if (!active) { var s = 0L; for (c in ch) s += apply(c); root.shown = if (root.kind == NodeKind.DIR) root.size else maxOf(root.size, s); return root.shown }
        var s = 0L
        for (c in ch) s += apply(c)
        root.shown = s
        return s
    }
}

object SpaceQueries {
    /** Largest files of the subtree (descending). */
    fun topFiles(root: SpaceNode, limit: Int = 100, filter: SpaceFilter = SpaceFilter()): List<SpaceNode> {
        val heap = java.util.PriorityQueue<SpaceNode>(limit + 1, compareBy { it.size })
        fun walk(n: SpaceNode) {
            if (n.kind == NodeKind.FILE) {
                if (filter.active && !filter.matches(n)) return
                if (heap.size < limit) heap.add(n) else if (n.size > heap.peek().size) { heap.poll(); heap.add(n) }
                return
            }
            n.children?.forEach { walk(it) }
        }
        walk(root)
        return heap.sortedByDescending { it.size }
    }

    /** Topmost directories whose subtree holds no file at all (nested empties are not repeated). */
    fun emptyDirs(root: SpaceNode, limit: Int = 500): List<SpaceNode> {
        val out = ArrayList<SpaceNode>()
        fun walk(n: SpaceNode) {
            if (out.size >= limit) return
            if (n.kind != NodeKind.DIR || n.has(NodeFlags.UNREADABLE)) return
            if (n.count == 0 && n.parent != null) { out.add(n); return }
            n.children?.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    /** All FILE nodes of at least [minBytes] (duplicate candidates). */
    fun filesAtLeast(root: SpaceNode, minBytes: Long): List<SpaceNode> {
        val out = ArrayList<SpaceNode>()
        fun walk(n: SpaceNode) {
            if (n.kind == NodeKind.FILE) { if (n.size >= minBytes) out.add(n); return }
            if (n.size < minBytes) return
            n.children?.forEach { walk(it) }
        }
        walk(root)
        return out
    }

    /** Removes [n] from its parent and subtracts its size/count from every ancestor (after a delete). */
    fun detach(n: SpaceNode) {
        val p = n.parent ?: return
        p.children?.remove(n)
        var a: SpaceNode? = p
        while (a != null) { a.size -= n.size; a.count -= n.count; a = a.parent }
        n.parent = null
    }

    /** Package folders of the subtree whose parent is an app-data root (Android/data|obb|media, /data/data…). */
    fun packageFolders(root: SpaceNode, isAppParent: (String) -> Boolean, packageOf: (String, String) -> String?): List<SpaceNode> {
        val out = ArrayList<SpaceNode>()
        fun walk(n: SpaceNode, depth: Int) {
            if (n.kind != NodeKind.DIR || depth > 8) return
            val p = n.path()
            if (isAppParent(p)) {
                n.children?.forEach { c -> if (c.kind == NodeKind.DIR) packageOf(p, c.name)?.let { pk -> c.pkg = pk; out.add(c) } }
                return
            }
            n.children?.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }
}

/** Compact binary cache of a tree (instant reopen). */
object SpaceCache {
    private const val MAGIC = 0x47534331 // GSC1
    private const val VERSION = 2

    fun write(out: DataOutputStream, tree: SpaceTree, meta: Map<String, String> = emptyMap()) {
        out.writeInt(MAGIC); out.writeInt(VERSION)
        out.writeLong(tree.scannedAt)
        out.writeInt(meta.size); meta.forEach { (k, v) -> out.writeUTF(k); out.writeUTF(v) }
        synchronized(tree.lock) { writeNode(out, tree.root) }
        out.flush()
    }

    private fun writeNode(out: DataOutputStream, n: SpaceNode) {
        out.writeByte(n.kind.ordinal)
        out.writeUTF(n.name)
        out.writeLong(n.size); out.writeInt(n.count); out.writeLong(n.mtime)
        out.writeByte(n.category.ordinal); out.writeInt(n.flags)
        out.writeUTF(n.pkg ?: ""); out.writeUTF(n.detail ?: "")
        val cs = n.catSizes
        out.writeByte(if (cs == null) 0 else cs.size)
        cs?.forEach { out.writeLong(it) }
        val ch = n.children
        out.writeInt(ch?.size ?: 0)
        ch?.forEach { writeNode(out, it) }
    }

    class Loaded(val tree: SpaceTree, val meta: Map<String, String>)

    fun read(input: DataInputStream): Loaded {
        if (input.readInt() != MAGIC) throw IOException("not a space cache")
        if (input.readInt() != VERSION) throw IOException("old space cache")
        val at = input.readLong()
        val meta = HashMap<String, String>()
        repeat(input.readInt()) { meta[input.readUTF()] = input.readUTF() }
        val root = readNode(input, null, 0)
        return Loaded(SpaceTree(root).also { it.scannedAt = at }, meta)
    }

    private fun readNode(input: DataInputStream, parent: SpaceNode?, depth: Int): SpaceNode {
        if (depth > 512) throw IOException("cache too deep")
        val kind = NodeKind.entries[input.readByte().toInt()]
        val n = SpaceNode(input.readUTF(), kind, parent)
        n.size = input.readLong(); n.count = input.readInt(); n.mtime = input.readLong()
        n.category = FileCategory.entries[input.readByte().toInt()]; n.flags = input.readInt()
        n.pkg = input.readUTF().ifEmpty { null }; n.detail = input.readUTF().ifEmpty { null }
        val cs = input.readByte().toInt()
        if (cs > 0) n.catSizes = LongArray(cs) { input.readLong() }
        val c = input.readInt()
        if (c > 0) {
            val list = ArrayList<SpaceNode>(c)
            repeat(c) { list.add(readNode(input, n, depth + 1)) }
            n.children = list
        }
        return n
    }
}
