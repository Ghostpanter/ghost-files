package com.ghostpanter.adb

import com.ghostpanter.files.core.ShellRunner
import com.ghostpanter.files.core.VPath
import java.io.ByteArrayInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Runs `sh -c` scripts on the target over the legacy (v1) `shell:<cmd>` service. That service is
 * raw (no PTY, binary-safe) with stderr merged into stdout and no exit status, so every script
 * is wrapped as `( script ); printf '<marker>%d\n' $?` and [AdbProcess] strips the marker and
 * reports the exit code. [root] runs the script through `su 0 sh -c` on the target.
 */
class AdbShellRunner(
    private val transport: () -> AdbTransport,
    private val root: Boolean = false,
    private val onBroken: (() -> Unit)? = null,
) : ShellRunner {

    override fun start(script: String): Process {
        val marker = "__GFRC_" + java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() or 1L) + ":"
        val body = if (root) "su 0 sh -c " + VPath.shellQuote(script) else script
        val wrapped = "(\n$body\n); printf '$marker%d\\n' \$?"
        val ch = try {
            openChannel(wrapped)
        } catch (e: IOException) {
            onBroken?.invoke()
            openChannel(wrapped) // one reconnect
        }
        return AdbProcess(ch, marker)
    }

    private fun openChannel(wrapped: String): AdbChannel {
        val t = transport()
        return if (wrapped.length <= INLINE_MAX) {
            t.open("shell:$wrapped")
        } else {
            // Too long for one OPEN payload: feed it to a raw `sh` through stdin.
            val ch = t.open("shell:sh")
            ch.output.write("{ $wrapped\n} </dev/null\nexit\n".toByteArray(Charsets.UTF_8))
            ch.output.flush()
            ch
        }
    }

    companion object {
        /** Conservative: OPEN payload max is 4 KiB on v1 adbd. */
        const val INLINE_MAX = 3800
    }
}

/** A [Process] over one ADB shell stream. Exit code comes from the sentinel line. */
class AdbProcess(private val ch: AdbChannel, marker: String) : Process() {
    private val done = CountDownLatch(1)
    @Volatile private var rc: Int? = null

    private val stdout = SentinelInputStream(ch.input, marker.toByteArray(Charsets.US_ASCII)) { code ->
        if (rc == null) rc = code
        done.countDown()
    }

    private val stdin = object : FilterOutputStream(ch.output) {
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        // v1 shell has no half-close: closing stdin must not close the whole stream.
        override fun close() { runCatching { out.flush() } }
    }

    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = stdout
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int { done.await(); return rc ?: 255 }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)

    override fun exitValue(): Int {
        if (done.count > 0) throw IllegalThreadStateException("running")
        return rc ?: 255
    }

    override fun destroy() {
        if (rc == null) rc = 143
        done.countDown()
        ch.close()
    }

    override fun destroyForcibly(): Process { destroy(); return this }
    override fun isAlive(): Boolean = done.count > 0
}

/**
 * Passes bytes through until [marker] appears, then parses the decimal exit code after it and
 * reports EOF. The wrapper prints the marker right after the script's own output (no extra
 * newline), so the data is exact. EOF without a marker reports exit 255.
 */
class SentinelInputStream(
    private val src: InputStream,
    private val marker: ByteArray,
    private val onExit: (Int) -> Unit,
) : InputStream() {
    private var buf = ByteArray(16 * 1024)
    private var len = 0      // valid bytes in buf
    private var pos = 0      // next byte to hand out
    private var safe = 0     // bytes [pos, safe) are confirmed data
    private var eof = false
    private var finished = false

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, n: Int): Int {
        if (n == 0) return 0
        while (pos >= safe) {
            if (finished) return -1
            fill()
        }
        val k = minOf(n, safe - pos)
        System.arraycopy(buf, pos, b, off, k)
        pos += k
        return k
    }

    override fun available(): Int = safe - pos

    private fun fill() {
        // compact
        if (pos > 0) {
            System.arraycopy(buf, pos, buf, 0, len - pos)
            len -= pos; safe -= pos; pos = 0
        }
        if (!eof) {
            if (len == buf.size) buf = buf.copyOf(buf.size * 2)
            val r = src.read(buf, len, buf.size - len)
            if (r < 0) eof = true else len += r
        }
        val idx = indexOf(buf, len, marker)
        if (idx >= 0) {
            // need the whole "<digits>\n" after the marker (or EOF)
            var end = idx + marker.size
            while (end < len && buf[end] != '\n'.code.toByte()) end++
            if (end >= len && !eof) { safe = maxOf(pos, dataEnd(idx)); return }
            val code = String(buf, idx + marker.size, end - idx - marker.size, Charsets.US_ASCII).trim().toIntOrNull() ?: 255
            safe = dataEnd(idx)
            len = safe
            finished = true
            onExit(code)
            return
        }
        if (eof) {
            safe = len
            finished = true
            onExit(255)
            return
        }
        // hold back only a tail that could be the start of the marker (line readers must see a
        // complete last line right away, e.g. the persistent shell's own end-of-command marker)
        safe = maxOf(pos, len - partialTail())
    }

    private fun dataEnd(markerIdx: Int): Int = markerIdx

    /** Longest suffix of the buffer that is a proper prefix of the marker. */
    private fun partialTail(): Int {
        var k = minOf(len - pos, marker.size - 1)
        while (k > 0) {
            var ok = true
            for (j in 0 until k) if (buf[len - k + j] != marker[j]) { ok = false; break }
            if (ok) return k
            k--
        }
        return 0
    }

    override fun close() { runCatching { src.close() } }

    companion object {
        fun indexOf(h: ByteArray, hl: Int, n: ByteArray): Int {
            if (n.isEmpty()) return 0
            outer@ for (i in 0..hl - n.size) {
                for (j in n.indices) if (h[i + j] != n[j]) continue@outer
                return i
            }
            return -1
        }
    }
}
