package com.ghostpanter.adb

import com.ghostpanter.files.core.ShellResult
import com.ghostpanter.files.core.VPath
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * High-level operations on one target device (serial = "host:port"). Every call blocks; run it
 * off the main thread. A failed stream open drops the pooled connection and retries once.
 */
class AdbDevice(val serial: String, private val transportProvider: () -> AdbTransport) {

    fun transport(): AdbTransport = transportProvider()

    private fun <T> withRetry(block: (AdbTransport) -> T): T = try {
        block(transport())
    } catch (e: IOException) {
        AdbPool.invalidate(serial)
        block(transport())
    }

    fun open(destination: String): AdbChannel = withRetry { it.open(destination) }

    fun runner(root: Boolean = false) = AdbShellRunner({ transport() }, root) { AdbPool.invalidate(serial) }

    /** Runs [cmd] with `sh -c` on the target and collects merged stdout+stderr and the exit code. */
    fun sh(cmd: String, root: Boolean = false, timeoutMs: Long = 30_000, maxBytes: Int = 8 shl 20): ShellResult {
        val (rc, bytes) = shBytes(cmd, root, timeoutMs, maxBytes)
        return ShellResult(rc, String(bytes, Charsets.UTF_8), "")
    }

    /** Like [sh] but returns raw (binary-safe) output bytes. */
    fun shBytes(cmd: String, root: Boolean = false, timeoutMs: Long = 30_000, maxBytes: Int = 8 shl 20): Pair<Int, ByteArray> {
        val p = runner(root).start(cmd)
        val out = ByteArrayOutputStream()
        val reader = thread(isDaemon = true, name = "adb-sh") {
            runCatching {
                val b = ByteArray(16 * 1024)
                while (true) {
                    val n = p.inputStream.read(b)
                    if (n < 0) break
                    if (out.size() < maxBytes) out.write(b, 0, minOf(n, maxBytes - out.size()))
                }
            }
        }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroy()
            throw IOException("adb shell timed out: ${cmd.take(60)}")
        }
        reader.join(2000)
        return p.exitValue() to out.toByteArray()
    }

    /** [len] bytes of [path] from [offset] via `dd` (seeks, never streams the prefix). */
    fun readRange(path: String, offset: Long, len: Int, root: Boolean = false): ByteArray {
        if (len <= 0) return ByteArray(0)
        val bs = if (len > (1 shl 20)) 65536L else 4096L
        val first = offset / bs
        val last = (offset + len - 1) / bs
        val count = last - first + 1
        val (_, raw) = shBytes("exec dd if=${q(path)} bs=$bs skip=$first count=$count 2>/dev/null", root,
            timeoutMs = 120_000, maxBytes = (count * bs).toInt() + 16)
        val skip = (offset - first * bs).toInt()
        if (raw.size <= skip) return ByteArray(0)
        return raw.copyOfRange(skip, minOf(raw.size, skip + len))
    }

    /** Size in bytes of a remote file (stat), or -1. */
    fun fileSize(path: String, root: Boolean = false): Long =
        runCatching { sh("stat -c %s ${q(path)}", root, 15_000).stdout.trim().toLong() }.getOrDefault(-1L)

    /** Opens a remote APK for ranged entry reads. */
    fun zip(path: String, root: Boolean = false): ZipRange {
        val size = fileSize(path, root)
        if (size <= 0) throw IOException("cannot stat $path")
        return ZipRange(size) { off, len -> readRange(path, off, len, root) }
    }

    /** stdout of [cmd], trimmed; throws on a non-zero exit. */
    fun text(cmd: String, root: Boolean = false, timeoutMs: Long = 30_000): String {
        val r = sh(cmd, root, timeoutMs)
        if (!r.ok) throw IOException(r.stdout.trim().ifEmpty { "exit ${r.code}" })
        return r.stdout.trimEnd()
    }

    /** True when `su` on the target grants uid 0. */
    fun hasRoot(): Boolean = runCatching { sh("id", root = true, timeoutMs = 15_000).stdout.contains("uid=0") }.getOrDefault(false)

    // ---- sync ----
    fun openRecv(remote: String): InputStream = SyncRecvInputStream(open("sync:"), remote)

    fun openSend(remote: String, mode: Int = 420, onDone: (() -> Unit)? = null): OutputStream =
        SyncSendOutputStream(open("sync:"), remote, mode, onClosed = onDone)

    fun pull(remote: String, dst: OutputStream): Long = openRecv(remote).use { it.copyTo(dst) }

    fun push(src: InputStream, remote: String, mode: Int = 420): Long {
        var n = 0L
        openSend(remote, mode).use { out -> n = src.copyTo(out) }
        return n
    }

    fun statSync(remote: String): Triple<Int, Long, Long>? = open("sync:").use { SyncWire.stat(it, remote) }

    /** Fire-and-forget ADB service such as "reboot:recovery" or "tcpip:5555" (reads its banner). */
    fun service(dest: String, timeoutMs: Long = 8_000): String {
        val ch = open(dest)
        val out = ByteArrayOutputStream()
        val t = thread(isDaemon = true, name = "adb-svc") { runCatching { ch.input.copyTo(out) } }
        t.join(timeoutMs)
        ch.close()
        return out.toString(Charsets.UTF_8.name()).trim()
    }

    /** Interactive PTY shell (`shell:` with no command). */
    fun openPty(): AdbChannel = open("shell:")

    companion object {
        fun q(s: String) = VPath.shellQuote(s)
    }
}
