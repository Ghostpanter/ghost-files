package com.ghostpanter.terminal

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Output side of a backend; called from a reader thread. */
interface TermSink {
    fun onOutput(text: String)
    fun onExit(reason: String?)
}

/** A shell behind one terminal tab. */
interface TermBackend {
    /** Short tab label, e.g. "本机", "Root", "Pixel 7". */
    val label: String
    /** Prompt parts: `user@host dir %`. Filled in by [start] when the backend can find out. */
    var user: String
    var host: String
    /** True when the shell runs on a PTY (raw keys, Ctrl-C via 0x03, programs like top work). */
    val pty: Boolean

    fun start(sink: TermSink, cols: Int, rows: Int)
    fun sendLine(line: String)
    fun sendRaw(bytes: ByteArray)
    fun interrupt()
    fun eof()
    fun resize(cols: Int, rows: Int) {}
    /** Blocking one-shot command (completion, user/host probing). Runs in [cwd] when given. */
    fun exec(cmd: String, cwd: String? = null): String
    fun close()

    /** Candidates for Tab completion of [word] (the last token of the input line). */
    fun complete(cwd: String, word: String, command: Boolean): List<String> {
        if (word.contains('\'') || word.contains('\n')) return emptyList()
        val out = if (command && !word.contains('/')) {
            exec("for d in \$(echo \"\$PATH\" | tr ':' ' '); do ls \"\$d\" 2>/dev/null; done | grep -- '^" + word.replace(".", "\\.") + "' | sort -u | head -200")
        } else {
            val dir = word.substringBeforeLast('/', "")
            val base = word.substringAfterLast('/')
            val listDir = when {
                word.startsWith("/") && dir.isEmpty() -> "/"
                dir.isEmpty() -> "."
                else -> dir
            }
            exec("ls -1ap -- '" + listDir + "' 2>/dev/null | grep -v -e '^\\./\$' -e '^\\.\\./\$' | grep -F -- '" + base + "' | head -300", cwd)
                .lines().filter { it.startsWith(base) }
                .joinToString("\n") { (if (word.startsWith("/") && dir.isEmpty()) "/" else if (dir.isEmpty()) "" else "$dir/") + it }
        }
        return out.lines().map { it.trimEnd('\r') }.filter { it.isNotEmpty() && it.startsWith(word) }.distinct()
    }

    companion object {
        /** printf that makes the shell report "ready, last rc, cwd" in-band. */
        const val PROMPT_PRINTF = "printf '\\036GFP%s;%s\\036' \"\$?\" \"\$PWD\""
        /** PS1 for PTY shells (mksh / sh expand parameters in PS1 at every prompt). */
        const val PS1 = "PS1=\"\$(printf '\\036')GFP\\\$?;\\\$PWD\$(printf '\\036')\"; PS2=''"
    }
}

/**
 * Backend over a pair of byte streams. [pty] = true: an interactive login shell on a terminal
 * (adb `shell:`); echo is turned off and PS1 reports prompts. [pty] = false: a plain `sh` reading
 * stdin (local / su / Shizuku); after each line we print the prompt record ourselves, and Ctrl-C
 * signals the shell's children with `pkill -INT -P <pid>` through [execOnce].
 */
class StreamShellBackend(
    override val label: String,
    override var user: String,
    override var host: String,
    override val pty: Boolean,
    private val open: () -> Triple<InputStream, OutputStream, Closeable>,
    private val execOnce: (cmd: String) -> String,
    /** Separate stderr stream (non-PTY processes), merged into the transcript. */
    private val stderr: (() -> InputStream?)? = null,
) : TermBackend {
    private var out: OutputStream? = null
    private var closer: Closeable? = null
    private val closed = AtomicBoolean()
    @Volatile private var shellPid = 0

    override fun start(sink: TermSink, cols: Int, rows: Int) {
        val (input, output, c) = open()
        out = output; closer = c
        val init = if (pty) {
            "stty -echo 2>/dev/null; stty cols $cols rows $rows 2>/dev/null; export TERM=dumb; ${TermBackend.PS1}; printf '\\036GFPID%s\\036' \$\$\n"
        } else {
            "export TERM=dumb; printf '\\036GFPID%s\\036' \$\$; ${TermBackend.PROMPT_PRINTF}\n"
        }
        write(init.toByteArray())
        val reader = { s: InputStream ->
            thread(isDaemon = true, name = "term-read") {
                val dec = Charsets.UTF_8.newDecoder()
                val buf = ByteArray(8192)
                val bb = java.nio.ByteBuffer.allocate(16384)
                val cb = java.nio.CharBuffer.allocate(16384)
                try {
                    while (true) {
                        val n = s.read(buf)
                        if (n < 0) break
                        bb.put(buf, 0, n); bb.flip()
                        dec.decode(bb, cb, false)
                        bb.compact(); cb.flip()
                        if (cb.hasRemaining()) sink.onOutput(cb.toString())
                        cb.clear()
                    }
                } catch (_: Exception) { }
            }
        }
        val main = reader(input)
        stderr?.invoke()?.let { reader(it) }
        thread(isDaemon = true, name = "term-wait") {
            main.join()
            if (!closed.get()) sink.onExit(null)
        }
    }

    fun onPid(pid: Int) { shellPid = pid }

    // Writes leave the caller's (main) thread: ADB streams are sockets (NetworkOnMainThread).
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "term-write").apply { isDaemon = true } }

    private fun write(b: ByteArray) {
        if (closed.get()) return
        runCatching {
            writer.execute {
                val o = out ?: return@execute
                try { synchronized(o) { o.write(b); o.flush() } } catch (_: Exception) { }
            }
        }
    }

    override fun sendLine(line: String) {
        val tail = if (pty) "" else "\n${TermBackend.PROMPT_PRINTF}"
        write((line + tail + "\n").toByteArray())
    }

    override fun sendRaw(bytes: ByteArray) = write(bytes)

    override fun interrupt() {
        if (pty) { write(byteArrayOf(3)); return }
        val pid = shellPid
        if (pid > 0) thread(isDaemon = true) { runCatching { execOnce("pkill -INT -P $pid 2>/dev/null || kill -INT \$(cat /proc/$pid/task/*/children 2>/dev/null) 2>/dev/null") } }
    }

    override fun eof() {
        if (pty) write(byteArrayOf(4)) else write("exit\n".toByteArray())
    }

    override fun resize(cols: Int, rows: Int) {
        // Only while the shell is idle at a prompt would this be safe to type; PTY shells pick it up at the next command.
    }

    override fun exec(cmd: String, cwd: String?): String =
        execOnce(if (cwd != null) "cd '" + cwd.replace("'", "'\\''") + "' 2>/dev/null; $cmd" else cmd)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val c = closer
        Thread({ runCatching { c?.close() } }, "term-close").start()
        writer.shutdown()
    }
}
