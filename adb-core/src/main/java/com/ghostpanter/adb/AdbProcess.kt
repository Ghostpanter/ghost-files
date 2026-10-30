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
        // PersistentShell boots with "exec sh". Give it a raw `shell:sh` stream with no
        // exit-code sentinel: the sentinel prefix "__GFRC_" shares "__GF" with
        // PersistentShell's "__GF_END_" markers and can corrupt line reads when packets split,
        // which surfaced as empty directory listings while sync push/pull still worked.
        if (script == "exec sh" && !root) {
            return AdbRawProcess(openRaw("shell:sh"))
        }
        val marker = "__GFRC_" + java.lang.Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong() or 1L) + ":"
        val body = if (root) "su 0 sh -c " + VPath.shellQuote(script) else script
        val multiline = '\n' in body
        // Keep the OPEN destination single-line when possible; embed real newlines only when
        // the script itself is multiline (those go through stdin below).
        val wrapped = if (multiline) "(\n$body\n); printf '$marker%d\\n' \$?" else "($body); printf '$marker%d\\n' \$?"
        return AdbProcess(openChannel(wrapped, forceStdin = multiline), marker)
    }

    private fun openRaw(dest: String): AdbChannel = try {
        transport().open(dest)
    } catch (e: IOException) {
        onBroken?.invoke()
        transport().open(dest)
    }

    private fun openChannel(wrapped: String, forceStdin: Boolean = false): AdbChannel {
        val t = try {
            transport()
        } catch (e: IOException) {
            onBroken?.invoke()
            transport()
        }
        // Prefer stdin feed when the script has newlines or is long: embedding multiline
        // payloads in the OPEN destination is fragile on some OEM adbd builds (only the
        // first line runs → cd succeeds, find never runs → empty list, exit 0).
        return if (!forceStdin && wrapped.length <= INLINE_MAX) {
            try {
                t.open("shell:$wrapped")
            } catch (e: IOException) {
                onBroken?.invoke()
                transport().open("shell:$wrapped")
            }
        } else {
            val ch = try {
                t.open("shell:sh")
            } catch (e: IOException) {
                onBroken?.invoke()
                transport().open("shell:sh")
            }
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

/**
 * Raw ADB shell process (no exit-code sentinel). Used for [PersistentShell]'s long-lived
 * `exec sh` session so stdout bytes are not held back / stripped by [SentinelInputStream].
 */
internal class AdbRawProcess(private val ch: AdbChannel) : Process() {
    @Volatile private var alive = true
    private val stdin = object : FilterOutputStream(ch.output) {
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
        override fun close() { runCatching { out.flush() } }
    }
    override fun getOutputStream(): OutputStream = stdin
    override fun getInputStream(): InputStream = ch.input
    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun waitFor(): Int { while (alive) Thread.sleep(50); return 0 }
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        val deadline = System.nanoTime() + unit.toNanos(timeout)
        while (alive && System.nanoTime() < deadline) Thread.sleep(20)
        return !alive
    }
    override fun exitValue(): Int {
        if (alive) throw IllegalThreadStateException("running")
        return 0
    }
    override fun destroy() { alive = false; ch.close() }
    override fun destroyForcibly(): Process { destroy(); return this }
    override fun isAlive(): Boolean = alive
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
