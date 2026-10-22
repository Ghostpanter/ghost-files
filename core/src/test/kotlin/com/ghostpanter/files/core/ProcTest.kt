package com.ghostpanter.files.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Behaves like Shizuku's ShizukuRemoteProcess: only the blocking waitFor() is real; waitFor(timeout) and isAlive()
 * are the java.lang.Process defaults, and exitValue() on a running child throws a plain
 * IllegalArgumentException("process hasn't exited") (what Binder turns IllegalThreadStateException into).
 */
private open class ShizukuLikeProcess(private val real: Process) : Process() {
    override fun getOutputStream(): OutputStream = real.outputStream
    override fun getInputStream(): InputStream = real.inputStream
    override fun getErrorStream(): InputStream = real.errorStream
    override fun waitFor(): Int = real.waitFor()
    override fun exitValue(): Int = try { real.exitValue() } catch (e: IllegalThreadStateException) { throw IllegalArgumentException("process hasn't exited") }
    override fun destroy() = real.destroy()
}

/** Scripted fake: exits after [exitAfterMs] (never when < 0) with [code]; stdout/stderr fed by [feed]. */
private class FakeProcess(private val code: Int, exitAfterMs: Long, feed: (OutputStream, OutputStream) -> Unit = { _, _ -> }) : Process() {
    private val done = CountDownLatch(1)
    @Volatile var destroyed = false
    private val outIn = PipedInputStream(4096); private val outW = PipedOutputStream(outIn)
    private val errIn = PipedInputStream(4096); private val errW = PipedOutputStream(errIn)
    init {
        thread(isDaemon = true) {
            runCatching { feed(outW, errW) }
            runCatching { outW.close() }; runCatching { errW.close() }
            if (exitAfterMs >= 0) { Thread.sleep(exitAfterMs); done.countDown() }
        }
    }
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = outIn
    override fun getErrorStream(): InputStream = errIn
    override fun waitFor(): Int { done.await(); return if (destroyed) 143 else code }
    override fun exitValue(): Int { if (done.count > 0) throw IllegalArgumentException("process hasn't exited"); return if (destroyed) 143 else code }
    override fun destroy() { destroyed = true; runCatching { outIn.close() }; runCatching { errIn.close() }; done.countDown() }
}

class ProcTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun sh(script: String) = ProcessBuilder("sh", "-c", script).start()

    @Test fun reproducesTheShizukuBugWithTheOldCalls() {
        val p = ShizukuLikeProcess(sh("sleep 0.5"))
        try {
            p.waitFor(5, TimeUnit.SECONDS)
            fail("default waitFor(timeout) should leak the binder exception")
        } catch (e: IllegalArgumentException) {
            assertEquals("process hasn't exited", e.message)
        }
        try { p.isAlive; fail("default isAlive() should leak it too") } catch (_: IllegalArgumentException) {}
        assertTrue(Proc.isAlive(p))
        assertEquals(0, Proc.await(p, 5000))
        assertFalse(Proc.isAlive(p))
    }

    @Test fun delayedExitIsAwaited() {
        val r = Proc.collect(ShizukuLikeProcess(sh("sleep 0.4; echo done")), 5000)
        assertEquals(0, r.code); assertEquals("done\n", r.stdout)
        val f = FakeProcess(0, exitAfterMs = 600) { o, _ -> o.write("late".toByteArray()) }
        val r2 = Proc.collect(f, 5000)
        assertEquals(0, r2.code); assertEquals("late", r2.stdout)
    }

    @Test fun emptyOutputIsFine() {
        val r = Proc.collect(ShizukuLikeProcess(sh("true")), 5000)
        assertEquals(0, r.code); assertEquals(0, r.out.size); assertEquals(0, r.err.size)
        val r2 = Proc.collect(FakeProcess(0, 50), 2000)
        assertTrue(r2.ok); assertEquals("", r2.stdout)
    }

    @Test fun largeOutputOnBothStreamsDoesNotDeadlock() {
        // 3 MB on stderr *before* stdout: a reader that drains stdout first would block forever.
        val r = Proc.collect(sh("head -c 3000000 /dev/zero >&2; head -c 5000000 /dev/zero; echo -n end >&2"), 20_000)
        assertEquals(0, r.code); assertEquals(5_000_000, r.out.size); assertEquals(3_000_003, r.err.size)
        val f = FakeProcess(0, 0) { o, e -> e.write(ByteArray(1 shl 20)); o.write(ByteArray(2 shl 20) { 7 }) }
        val r2 = Proc.collect(f, 10_000)
        assertEquals(2 shl 20, r2.out.size); assertEquals(1 shl 20, r2.err.size); assertEquals(7.toByte(), r2.out.last())
    }

    @Test fun outputCapStillDrains() {
        val r = Proc.collect(sh("head -c 1000000 /dev/zero"), 10_000, maxBytes = 1000)
        assertEquals(0, r.code); assertEquals(1000, r.out.size); assertTrue(r.truncated)
    }

    @Test fun timeoutDestroysAndThrows() {
        val f = FakeProcess(0, exitAfterMs = -1)
        val t0 = System.nanoTime()
        try { Proc.collect(f, 300); fail("expected timeout") } catch (e: ProcTimeoutException) { assertEquals(300, e.timeoutMs) }
        assertTrue(f.destroyed)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 3000)
        val real = sh("sleep 30")
        try { Proc.collect(real, 300); fail() } catch (_: ProcTimeoutException) {}
        assertEquals(null, Proc.await(sh("sleep 5"), 100))
        assertTrue(real.waitFor(5, TimeUnit.SECONDS))
    }

    @Test fun nonZeroExitCodeAndStderr() {
        val r = Proc.collect(ShizukuLikeProcess(sh("echo out; echo oops >&2; exit 3")), 5000)
        assertEquals(3, r.code); assertFalse(r.ok); assertEquals("out\n", r.stdout); assertEquals("oops\n", r.stderr)
        assertEquals(5, Proc.collect(FakeProcess(5, 10), 2000).code)
    }

    @Test fun afterStreamGivesCodeAndStderr() {
        val p = ShizukuLikeProcess(sh("echo hello; echo warn >&2; sleep 0.2; exit 2"))
        val finish = Proc.afterStream(p)
        assertEquals("hello\n", p.inputStream.readBytes().decodeToString())
        val (code, err) = finish(5000)
        assertEquals(2, code); assertEquals("warn\n", err)
    }

    /** The user's case: Shizuku ShellFs listing an empty Android/data folder, several times in a row. */
    @Test fun shellFsOverShizukuLikeProcessListsEmptyDirs() {
        val dir = tmp.newFolder("adImage")
        val full = tmp.newFolder("full").also { java.io.File(it, "a.jpg").writeText("x"); java.io.File(it, "sub").mkdir() }
        val runner = ShellRunner { s -> ShizukuLikeProcess(ProcessBuilder("sh", "-c", s).start()) }
        for (persistent in listOf(true, false)) {
            val fs = ShellFs("shizuku", "Shizuku", runner, persistent = persistent)
            repeat(5) {
                assertEquals(emptyList<FileEntry>(), fs.list(dir.path))
                assertEquals(setOf("a.jpg", "sub"), fs.list(full.path).map { it.name }.toSet())
            }
            assertEquals("x", fs.openRead(java.io.File(full, "a.jpg").path).use { it.readBytes().decodeToString() })
            fs.openWrite(java.io.File(dir, "w.txt").path).use { it.write("hi".toByteArray()) }
            val b = ByteArray(2)
            assertEquals(2, fs.readAt(java.io.File(dir, "w.txt").path, 0, b, 0, 2)); assertArrayEquals("hi".toByteArray(), b)
            fs.delete(java.io.File(dir, "w.txt").path, false)
            fs.close()
        }
    }
}
