package com.ghostpanter.files.core

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Spawns `sh -c <script>` in some privileged context (su, Shizuku, plain sh in tests). */
fun interface ShellRunner {
    fun start(script: String): Process
}

class ShellResult(val code: Int, val stdout: String, val stderr: String) {
    val ok get() = code == 0
}

/**
 * File system driven through shell commands (toybox / coreutils). Used for
 * Root (su) and Shizuku (adb shell uid), which can see hidden and system
 * areas such as /data, /Android/data and /Android/obb.
 */
class ShellFs(
    override val id: String,
    override val displayName: String,
    private val runner: ShellRunner,
    override val homePath: String = VPath.ROOT,
    private val timeoutSec: Long = 60,
    persistent: Boolean = true,
) : VirtualFs {

    override val capabilities = setOf(
        Capability.RESUME_READ, Capability.RESUME_WRITE, Capability.SERVER_COPY,
        Capability.RENAME_MOVE, Capability.CHMOD, Capability.ELEVATED,
    )

    private fun q(p: String) = VPath.shellQuote(VPath.normalize(p))

    private val session = if (persistent) PersistentShell(runner) else null

    fun exec(script: String, timeout: Long = timeoutSec): ShellResult {
        session?.let { sh ->
            // One long-lived su/Shizuku shell for short commands (list/stat/mkdir…): avoids a process spawn
            // (and su round-trip) per call. Falls back to a fresh process when the session can't be used.
            try { return sh.exec(script, timeout) } catch (e: PersistentShell.Unavailable) { /* fall through */ }
        }
        return execOnce(script, timeout)
    }

    override fun close() { session?.close() }

    private fun execOnce(script: String, timeout: Long): ShellResult {
        val p = runner.start(script)
        p.outputStream.close()
        val err = ByteArrayOutputStream()
        val errThread = thread(isDaemon = true, name = "shell-stderr") {
            runCatching { p.errorStream.copyTo(err) }
        }
        val out = ByteArrayOutputStream()
        val outThread = thread(isDaemon = true, name = "shell-stdout") {
            runCatching { p.inputStream.copyTo(out) }
        }
        if (!p.waitFor(timeout, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw FsException(tr("命令超时 (${timeout}s)", "Command timed out (${timeout}s)"))
        }
        outThread.join(2000)
        errThread.join(2000)
        return ShellResult(p.exitValue(), out.toString(Charsets.UTF_8.name()), err.toString(Charsets.UTF_8.name()))
    }

    private fun run(script: String, what: String): ShellResult {
        val r = exec(script)
        if (!r.ok) throw FsException(tr("$what 失败", "$what failed") + ": ${r.stderr.trim().ifEmpty { "exit ${r.code}" }}")
        return r
    }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> {
        val dir = VPath.normalize(path)
        val script = "cd -- ${q(dir)} || exit 2\n" +
            "find . -mindepth 1 -maxdepth 1 -exec stat -c '$FMT' {} + 2>/dev/null\n" +
            "echo '$SEP'\n" +
            "find -L . -mindepth 1 -maxdepth 1 -type d 2>/dev/null\n" +
            "exit 0"
        val r = exec(script)
        if (r.code == 2) throw FsException(tr("无法进入目录: $dir ${r.stderr.trim()}", "Cannot open directory (permission denied?): $dir ${r.stderr.trim()}"))
        val result = parseListing(dir, r.stdout)
        onChunk?.invoke(result)
        return result
    }

    override fun stat(path: String): FileEntry? {
        val p = VPath.normalize(path)
        val r = exec("stat -c '$FMT' -- ${q(p)} || exit 1\n[ -d ${q(p)} ] && echo '$SEP_DIR' ; exit 0")
        if (!r.ok) return null
        val lines = r.stdout.lines().filter { it.isNotEmpty() }
        val line = lines.firstOrNull { it != SEP_DIR } ?: return null
        val e = parseStatLine(VPath.parent(p), line, linkDirs = emptySet(), statPath = p) ?: return null
        return if (SEP_DIR in lines && !e.isDirectory) e.copy(isDirectory = true, size = 0) else e
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val p = q(path)
        val script = if (offset > 0) "exec tail -c +${offset + 1} -- $p" else "exec cat -- $p"
        val proc = runner.start(script)
        proc.outputStream.close()
        val err = ByteArrayOutputStream()
        thread(isDaemon = true, name = "shell-read-err") { runCatching { proc.errorStream.copyTo(err) } }
        return object : FilterInputStream(proc.inputStream) {
            private var checked = false
            private fun checkEof() {
                if (checked) return
                checked = true
                proc.waitFor(10, TimeUnit.SECONDS)
                val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                if (code != 0) throw FsException(tr("读取失败", "Read failed") + ": ${err.toString().trim().ifEmpty { "exit $code" }}")
            }
            override fun read(): Int { val b = super.read(); if (b < 0) checkEof(); return b }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len); if (n < 0) checkEof(); return n
            }
            override fun close() { runCatching { super.close() }; proc.destroy() }
        }
    }

    /**
     * Ranged read with `dd` (seeks with skip=, never streams the file prefix). Over the persistent session the
     * bytes come back base64-encoded (that channel is line/text based); otherwise a one-shot binary `dd` process.
     */
    override fun readAt(path: String, offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        if (len <= 0) return 0
        val bs = 4096L
        val first = offset / bs
        val last = (offset + len - 1) / bs
        val dd = "dd if=${q(path)} bs=$bs skip=$first count=${last - first + 1} 2>/dev/null"
        val raw: ByteArray = session?.let { sh ->
            try {
                val r = sh.exec("$dd | base64", timeoutSec)
                if (r.ok) java.util.Base64.getMimeDecoder().decode(r.stdout) else null
            } catch (_: PersistentShell.Unavailable) { null } catch (_: IllegalArgumentException) { null }
        } ?: run {
            val proc = runner.start("exec $dd")
            proc.outputStream.close()
            try { proc.inputStream.readBytes() } finally { proc.destroy() }
        }
        val skip = (offset - first * bs).toInt()
        if (raw.size <= skip) return -1
        val n = minOf(len, raw.size - skip)
        System.arraycopy(raw, skip, buf, off, n)
        return n
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream {
        val p = q(path)
        val script = "mkdir -p -- ${q(VPath.parent(path))} 2>/dev/null\n" +
            if (offset > 0) "truncate -s $offset -- $p 2>/dev/null; exec cat >> $p" else "exec cat > $p"
        val proc = runner.start(script)
        val err = ByteArrayOutputStream()
        thread(isDaemon = true, name = "shell-write-err") { runCatching { proc.errorStream.copyTo(err) } }
        thread(isDaemon = true, name = "shell-write-out") { runCatching { val b = ByteArray(4096); while (proc.inputStream.read(b) >= 0) Unit } }
        return object : FilterOutputStream(proc.outputStream) {
            private var closed = false
            override fun write(b: ByteArray, off: Int, len: Int) {
                try { out.write(b, off, len) } catch (e: IOException) {
                    throw FsException(tr("写入失败", "Write failed") + ": ${err.toString().trim().ifEmpty { e.message ?: "" }}", e)
                }
            }
            override fun close() {
                if (closed) return
                closed = true
                runCatching { out.flush() }
                runCatching { out.close() }
                if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                    proc.destroyForcibly(); throw FsException(tr("写入超时", "Write timed out"))
                }
                if (proc.exitValue() != 0) throw FsException(tr("写入失败", "Write failed") + ": ${err.toString().trim().ifEmpty { "exit ${proc.exitValue()}" }}")
            }
        }
    }

    override fun mkdirs(path: String) { run("mkdir -p -- ${q(path)}", tr("创建目录", "mkdir")) }

    override fun delete(path: String, recursive: Boolean) {
        val p = q(path)
        run(if (recursive) "rm -rf -- $p" else "if [ -d $p ] && [ ! -L $p ]; then rmdir -- $p; else rm -f -- $p; fi", tr("删除", "delete"))
    }

    override fun rename(from: String, to: String) {
        run("[ -e ${q(to)} ] && { echo '目标已存在' >&2; exit 17; }\nmv -- ${q(from)} ${q(to)}", tr("移动", "move"))
    }

    override fun serverCopy(from: String, to: String): Boolean =
        exec("cp -a -- ${q(from)} ${q(to)} 2>/dev/null || cp -r -- ${q(from)} ${q(to)}").ok

    override fun chmod(path: String, mode: String, recursive: Boolean) {
        require(mode.matches(Regex("[0-7]{3,4}|[ugoa]*[+-=][rwxXst]+"))) { tr("非法权限: $mode", "Invalid mode: $mode") }
        run("chmod ${if (recursive) "-R " else ""}$mode -- ${q(path)}", tr("修改权限", "chmod"))
    }

    fun chown(path: String, owner: String, recursive: Boolean = false) {
        require(owner.matches(Regex("[A-Za-z0-9_.:-]+"))) { tr("非法所有者: $owner", "Invalid owner: $owner") }
        run("chown ${if (recursive) "-R " else ""}$owner -- ${q(path)}", tr("修改所有者", "chown"))
    }

    override fun setLastModified(path: String, millis: Long) {
        if (millis > 0) exec("touch -m -d @${millis / 1000} -- ${q(path)} 2>/dev/null")
    }

    companion object {
        const val FMT = "%F\t%A\t%s\t%Y\t%U\t%G\t%n"
        const val SEP = "--GHOST-LINKDIRS--"
        const val SEP_DIR = "--GHOST-ISDIR--"

        /** Parses the output of the list script; public for tests. */
        fun parseListing(dir: String, out: String): List<FileEntry> {
            val sepIdx = out.indexOf(SEP)
            val statPart = if (sepIdx >= 0) out.substring(0, sepIdx) else out
            val linkDirs = if (sepIdx >= 0) {
                out.substring(sepIdx + SEP.length).lineSequence()
                    .map { it.removePrefix("./") }.filter { it.isNotEmpty() }.toHashSet()
            } else emptySet()
            return statPart.lineSequence().mapNotNull { parseStatLine(dir, it, linkDirs, null) }.toList()
        }

        fun parseStatLine(dir: String, line: String, linkDirs: Set<String>, statPath: String?): FileEntry? {
            val parts = line.split('\t', limit = 7)
            if (parts.size < 7) return null
            val type = parts[0]
            val rawName = parts[6].removePrefix("./")
            val name = if (statPath != null) VPath.name(statPath) else rawName.substringAfterLast('/')
            if (name.isEmpty() || name == "." || name == "..") return null
            val isLink = type.contains("link")
            val isDir = type == "directory" || (isLink && name in linkDirs)
            return FileEntry(
                path = statPath ?: VPath.join(dir, name),
                name = name,
                isDirectory = isDir,
                size = if (isDir) 0 else parts[2].toLongOrNull() ?: 0,
                lastModified = (parts[3].toLongOrNull() ?: 0) * 1000,
                isLink = isLink,
                permissions = parts[1],
                owner = parts[4],
                group = parts[5],
            )
        }
    }
}


/**
 * A single long-running `sh` behind [runner] that executes scripts one at a time. Each script runs in a subshell
 * with stdin from /dev/null and is followed by a random end marker on stdout and stderr. A timeout or broken pipe
 * kills the shell; the next call respawns it. Callers fall back to one-shot processes on [Unavailable].
 */
internal class PersistentShell(private val runner: ShellRunner) : java.io.Closeable {
    class Unavailable(cause: Throwable?) : Exception(cause)

    private val lock = Any()
    private var proc: Process? = null
    private var stdout: java.io.BufferedReader? = null
    private var stderr: java.io.BufferedReader? = null
    private var failures = 0
    private var disabledUntil = 0L
    private val pool = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "pshell-io").apply { isDaemon = true } }

    private fun ensure(): Process {
        proc?.takeIf { it.isAlive }?.let { return it }
        kill()
        val p = runner.start("exec sh")
        proc = p
        stdout = p.inputStream.bufferedReader(Charsets.UTF_8)
        stderr = p.errorStream.bufferedReader(Charsets.UTF_8)
        return p
    }

    fun exec(script: String, timeoutSec: Long): ShellResult = synchronized(lock) {
        if (System.currentTimeMillis() < disabledUntil) throw Unavailable(null)
        val mark = "__GF_END_" + java.util.UUID.randomUUID().toString().replace("-", "") + "__"
        try {
            val p = ensure()
            val out = stdout!!; val err = stderr!!
            // stderr marker first: when the runner merges stderr into stdout (RootRunner uses redirectErrorStream),
            // it then shows up on stdout *before* the stdout marker and is skipped there.
            val cmd = "(\n$script\n) </dev/null\nrc=\$?\nprintf '\\n%s\\n' '${mark}E' >&2\nprintf '\\n%s %d\\n' '$mark' \$rc\n"
            p.outputStream.write(cmd.toByteArray(Charsets.UTF_8)); p.outputStream.flush()
            val outF = pool.submit(java.util.concurrent.Callable { readUntil(out, mark, skip = mark + "E") })
            val errF = pool.submit(java.util.concurrent.Callable { readUntil(err, mark + "E", eofOk = true) })
            val (o, codeStr) = outF.get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS)
            val (e, _) = errF.get(5, java.util.concurrent.TimeUnit.SECONDS)
            failures = 0
            ShellResult(codeStr.trim().toIntOrNull() ?: -1, o, e)
        } catch (e: java.util.concurrent.TimeoutException) {
            kill()
            throw FsException(tr("命令超时 (${timeoutSec}s)", "Command timed out (${timeoutSec}s)"))
        } catch (e: Throwable) {
            kill()
            // Repeated failures (e.g. su denies a persistent shell): stop trying for a while.
            if (++failures >= 3) { disabledUntil = System.currentTimeMillis() + 60_000; failures = 0 }
            throw Unavailable(e)
        }
    }

    /** Returns (text before the marker, rest of the marker line). Strips the newline we print before it. */
    private fun readUntil(r: java.io.BufferedReader, mark: String, skip: String? = null, eofOk: Boolean = false): Pair<String, String> {
        val sb = StringBuilder()
        while (true) {
            val line = r.readLine() ?: if (eofOk) return "" to "" else throw java.io.EOFException("shell closed")
            if (skip != null && line == skip) {
                // drop the blank line printed before the merged stderr marker
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.setLength(sb.length - 1)
                continue
            }
            if (line.startsWith(mark) && (skip == null || line != skip)) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\n') sb.setLength(sb.length - 1)
                return sb.toString() to line.removePrefix(mark)
            }
            sb.append(line).append('\n')
        }
    }

    private fun kill() {
        proc?.let { runCatching { it.outputStream.close() }; runCatching { it.destroyForcibly() } }
        proc = null; stdout = null; stderr = null
    }

    override fun close() = synchronized(lock) { kill() }
}
