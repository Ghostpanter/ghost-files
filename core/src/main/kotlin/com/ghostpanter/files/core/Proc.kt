package com.ghostpanter.files.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Result of a finished child process. */
class ProcResult(val code: Int, val out: ByteArray, val err: ByteArray, val truncated: Boolean = false) {
    val ok get() = code == 0
    val stdout: String get() = out.toString(Charsets.UTF_8)
    val stderr: String get() = err.toString(Charsets.UTF_8)
    fun toShell() = ShellResult(code, stdout, stderr)
}

/** The process did not exit in time; it has been destroyed. */
class ProcTimeoutException(val timeoutMs: Long, val what: String? = null) :
    IOException(tr("命令超时（${(timeoutMs + 999) / 1000} 秒）", "Command timed out (${(timeoutMs + 999) / 1000}s)") + (what?.let { ": $it" } ?: ""))

/**
 * Process helpers that work for every [Process] implementation we use: java.lang.ProcessImpl (sh, su),
 * Shizuku's ShizukuRemoteProcess and our AdbProcess.
 *
 * Why not `waitFor(timeout)` / `isAlive()` / `exitValue()`: ShizukuRemoteProcess does not override
 * `waitFor(long, TimeUnit)` or `isAlive()`, so the java.lang.Process defaults run. Those poll `exitValue()` and
 * expect IllegalThreadStateException while the child runs; over Binder that arrives as a plain
 * IllegalArgumentException("process hasn't exited"), which the defaults don't catch and which escapes to the UI.
 * Only the blocking `waitFor()` is safe everywhere, so we run it on a helper thread and bound it with a timeout.
 */
object Proc {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "proc-io").apply { isDaemon = true } }

    private fun <T> join(f: Future<T>, ms: Long): T? = try { f.get(ms.coerceAtLeast(0), TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { null }

    private fun unwrap(e: ExecutionException): Throwable = e.cause ?: e

    /** Exit code, or null when [timeoutMs] passed first (the process is left running). */
    fun await(p: Process, timeoutMs: Long): Int? {
        val f = pool.submit(Callable { p.waitFor() })
        return try {
            f.get(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            f.cancel(true); null
        } catch (e: ExecutionException) {
            throw IOException(tr("等待进程结束失败", "Waiting for the process failed") + ": " + (unwrap(e).message ?: unwrap(e).javaClass.simpleName), unwrap(e))
        }
    }

    /** True while the child runs. Never throws (a dead binder counts as not alive). */
    fun isAlive(p: Process): Boolean = try {
        p.exitValue(); false
    } catch (_: IllegalArgumentException) {
        // IllegalThreadStateException locally; IllegalArgumentException("process hasn't exited") over Binder.
        true
    } catch (_: IllegalStateException) {
        true
    } catch (_: Throwable) {
        false
    }

    /** Kills [p] without throwing. */
    fun kill(p: Process) {
        runCatching { p.destroy() }
        runCatching { if (isAlive(p)) p.destroyForcibly() }
    }

    private class Drain(val buf: ByteArrayOutputStream, val future: Future<Boolean>)

    private fun drain(ins: InputStream?, max: Long): Drain {
        val buf = ByteArrayOutputStream()
        val f = pool.submit(Callable {
            var truncated = false
            if (ins != null) runCatching {
                ins.use { s ->
                    val b = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = s.read(b)
                        if (n < 0) break
                        if (total < max) buf.write(b, 0, minOf(n.toLong(), max - total).toInt()) else truncated = true
                        total += n
                    }
                }
            }
            truncated
        })
        return Drain(buf, f)
    }

    /**
     * Collects stdout and stderr concurrently (so neither pipe can fill and block the child), then waits for the
     * exit with [timeoutMs]. On timeout the process is destroyed and [ProcTimeoutException] is thrown.
     * Output beyond [maxBytes] per stream is discarded (still read, so the child is never blocked).
     * Empty output is fine and yields empty arrays.
     */
    fun collect(p: Process, timeoutMs: Long, closeStdin: Boolean = true, maxBytes: Long = 64L shl 20, what: String? = null): ProcResult {
        if (closeStdin) runCatching { p.outputStream.close() }
        val out = drain(runCatching { p.inputStream }.getOrNull(), maxBytes)
        val err = drain(runCatching { p.errorStream }.getOrNull(), maxBytes)
        val code = await(p, timeoutMs)
        if (code == null) {
            kill(p)
            out.future.cancel(true); err.future.cancel(true)
            throw ProcTimeoutException(timeoutMs, what)
        }
        // The child exited; its pipes hit EOF unless a grandchild kept them open. Don't wait forever for that.
        val t1 = join(out.future, 3000) ?: run { runCatching { p.inputStream.close() }; false }
        val t2 = join(err.future, 1000) ?: run { runCatching { p.errorStream.close() }; false }
        return ProcResult(code, out.buf.toByteArray(), err.buf.toByteArray(), t1 || t2)
    }

    /** `runner.start(script)` + [collect]. */
    fun run(runner: ShellRunner, script: String, timeoutMs: Long, what: String? = null): ProcResult =
        collect(runner.start(script), timeoutMs, what = what)

    /**
     * For streaming callers that read stdout themselves: drains stderr in the background and returns a function
     * that, after stdout hit EOF, waits for the exit code (null = timed out, process destroyed) plus stderr text.
     */
    fun afterStream(p: Process, maxErr: Long = 1L shl 20): (timeoutMs: Long) -> Pair<Int?, String> {
        val err = drain(runCatching { p.errorStream }.getOrNull(), maxErr)
        return { ms ->
            val code = await(p, ms)
            if (code == null) kill(p)
            join(err.future, 500)
            code to err.buf.toString(Charsets.UTF_8)
        }
    }
}
