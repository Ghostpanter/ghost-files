package com.ghostpanter.files.core.transfer

import com.ghostpanter.files.core.FlakyFs
import com.ghostpanter.files.core.HangingFs
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.SlowFs
import com.ghostpanter.files.core.VirtualFs
import com.ghostpanter.files.core.tempDir
import com.ghostpanter.files.core.writeRandom
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TransferEngineTest {

    private val fast = TransferConfig(initialBackoffMs = 10, maxBackoffMs = 50, bufferSize = 16 * 1024, stallTimeoutMs = 1500)

    private fun engine(vararg fs: VirtualFs, cfg: TransferConfig = fast): TransferEngine {
        val map = fs.associateBy { it.id }
        return TransferEngine({ map.getValue(it) }, cfg)
    }

    private fun run(e: TransferEngine, id: Long): JobSnapshot = runBlocking { withTimeout(30_000) { e.await(id) } }

    @Test fun copiesDirectoryTreeAcrossBackends() {
        val src = tempDir(); val dst = tempDir()
        val a = File(src, "dir/a.bin").writeRandom(100_000, 1)
        val b = File(src, "dir/sub/b.bin").writeRandom(5_000, 2)
        File(src, "dir/empty").mkdirs()
        val local = LocalFs()
        val other = LocalFs(id = "other")
        val e = engine(local, other)
        val id = e.enqueue(TransferOp.COPY, listOf(Loc("local", File(src, "dir").path)), Loc("other", dst.path))
        val s = run(e, id)
        assertEquals(JobState.COMPLETED, s.state)
        assertEquals(2, s.totalFiles)
        assertEquals(105_000L, s.totalBytes)
        assertEquals(105_000L, s.doneBytes)
        assertArrayEquals(a, File(dst, "dir/a.bin").readBytes())
        assertArrayEquals(b, File(dst, "dir/sub/b.bin").readBytes())
        assertTrue(File(dst, "dir/empty").isDirectory)
        assertTrue(File(src, "dir/a.bin").exists())
    }

    @Test fun moveAcrossBackendsDeletesSources() {
        val src = tempDir(); val dst = tempDir()
        val data = File(src, "m/x.bin").writeRandom(50_000)
        val e = engine(LocalFs(), LocalFs(id = "other"))
        val s = run(e, e.enqueue(TransferOp.MOVE, listOf(Loc("local", File(src, "m").path)), Loc("other", dst.path)))
        assertEquals(JobState.COMPLETED, s.state)
        assertArrayEquals(data, File(dst, "m/x.bin").readBytes())
        assertFalse(File(src, "m").exists())
    }

    @Test fun sameBackendMoveUsesRename() {
        val root = tempDir()
        File(root, "s/f.txt").writeRandom(10)
        File(root, "d").mkdirs()
        val e = engine(LocalFs())
        val s = run(e, e.enqueue(TransferOp.MOVE, listOf(Loc("local", File(root, "s").path)), Loc("local", File(root, "d").path)))
        assertEquals(JobState.COMPLETED, s.state)
        assertEquals(1, s.serverSideFiles)
        assertTrue(File(root, "d/s/f.txt").exists())
        assertFalse(File(root, "s").exists())
    }

    @Test fun retriesAndResumesFromOffsetAfterNetworkDrop() {
        val src = tempDir(); val dst = tempDir()
        val data = File(src, "big.bin").writeRandom(300_000)
        val flaky = FlakyFs(LocalFs(), "flaky", failOpens = 2, failAfter = 100_000)
        val e = engine(flaky, LocalFs())
        val s = run(e, e.enqueue(TransferOp.COPY, listOf(Loc("flaky", File(src, "big.bin").path)), Loc("local", dst.path)))
        assertEquals(JobState.COMPLETED, s.state)
        assertEquals(2, s.retries)
        assertArrayEquals(data, File(dst, "big.bin").readBytes())
        // first attempt from 0, retries continue where the partial file ended
        assertEquals(0L, flaky.readOffsets[0])
        assertTrue("resumed offsets: ${flaky.readOffsets}", flaky.readOffsets[1] >= 100_000)
        assertTrue(flaky.readOffsets[2] > flaky.readOffsets[1])
    }

    @Test fun givesUpAfterMaxRetriesButContinuesOtherFiles() {
        val src = tempDir(); val dst = tempDir()
        File(src, "a.bin").writeRandom(50_000)
        val flaky = FlakyFs(LocalFs(), "flaky", failOpens = 100, failAfter = 1_000)
        val e = engine(flaky, LocalFs(), cfg = fast.copy(maxRetries = 2))
        val s = run(e, e.enqueue(TransferOp.COPY, listOf(Loc("flaky", File(src, "a.bin").path)), Loc("local", dst.path)))
        assertEquals(JobState.FAILED, s.state)
        assertEquals(1, s.failures.size)
    }

    @Test fun watchdogAbortsStalledStreamAndRetries() {
        val src = tempDir(); val dst = tempDir()
        val data = File(src, "s.bin").writeRandom(20_000)
        val hanging = HangingFs(LocalFs(), "hang")
        val e = engine(hanging, LocalFs())
        val started = System.currentTimeMillis()
        val s = run(e, e.enqueue(TransferOp.COPY, listOf(Loc("hang", File(src, "s.bin").path)), Loc("local", dst.path)))
        assertEquals(JobState.COMPLETED, s.state)
        assertTrue(System.currentTimeMillis() - started < 15_000)
        assertEquals(2, hanging.opens.get())
        assertArrayEquals(data, File(dst, "s.bin").readBytes())
    }

    @Test fun conflictPolicies() {
        val src = tempDir(); val dst = tempDir()
        val data = File(src, "c.txt").writeRandom(1000, 7)
        File(dst, "c.txt").writeRandom(10, 8)
        val e = engine(LocalFs(), LocalFs(id = "o"))
        val loc = listOf(Loc("local", File(src, "c.txt").path))
        assertEquals(1, run(e, e.enqueue(TransferOp.COPY, loc, Loc("o", dst.path), ConflictPolicy.SKIP)).skippedFiles)
        assertEquals(10, File(dst, "c.txt").length())
        run(e, e.enqueue(TransferOp.COPY, loc, Loc("o", dst.path), ConflictPolicy.RENAME))
        assertArrayEquals(data, File(dst, "c (1).txt").readBytes())
        run(e, e.enqueue(TransferOp.COPY, loc, Loc("o", dst.path), ConflictPolicy.OVERWRITE))
        assertArrayEquals(data, File(dst, "c.txt").readBytes())
    }

    @Test fun resumePolicyContinuesPartialFile() {
        val src = tempDir(); val dst = tempDir()
        val data = File(src, "r.bin").writeRandom(200_000)
        File(dst, "r.bin").writeBytes(data.copyOf(120_000))
        val flaky = FlakyFs(LocalFs(), "f", failOpens = 0, failAfter = 0)
        val e = engine(flaky, LocalFs())
        val s = run(e, e.enqueue(TransferOp.COPY, listOf(Loc("f", File(src, "r.bin").path)), Loc("local", dst.path), ConflictPolicy.RESUME))
        assertEquals(JobState.COMPLETED, s.state)
        assertEquals(listOf(120_000L), flaky.readOffsets.toList())
        assertArrayEquals(data, File(dst, "r.bin").readBytes())
    }

    @Test fun refusesCopyIntoItself() {
        val root = tempDir()
        File(root, "p/q").mkdirs()
        val e = engine(LocalFs())
        val s = run(e, e.enqueue(TransferOp.COPY, listOf(Loc("local", File(root, "p").path)), Loc("local", File(root, "p/q").path)))
        assertEquals(JobState.FAILED, s.state)
    }

    @Test fun copyIntoSameDirectoryMakesNumberedDuplicate() {
        val root = tempDir()
        File(root, "same.txt").writeRandom(100)
        val e = engine(LocalFs())
        val s = run(e, e.enqueue(TransferOp.COPY, listOf(Loc("local", File(root, "same.txt").path)), Loc("local", root.path)))
        assertEquals(JobState.COMPLETED, s.state)
        assertTrue(File(root, "same (1).txt").exists())
    }

    @Test fun pauseThenResumeCompletes() = runBlocking {
        val src = tempDir(); val dst = tempDir()
        val data = File(src, "p.bin").writeRandom(600_000)
        val e = engine(SlowFs(LocalFs(), "slow", 15), LocalFs())
        val id = e.enqueue(TransferOp.COPY, listOf(Loc("slow", File(src, "p.bin").path)), Loc("local", dst.path))
        withTimeout(10_000) { while (e.jobs.value.first { it.id == id }.doneBytes < 50_000) delay(20) }
        e.pause(id)
        delay(300)
        val paused = e.jobs.value.first { it.id == id }
        assertEquals(JobState.PAUSED, paused.state)
        val partial = File(dst, "p.bin").length()
        delay(300)
        assertEquals(partial, File(dst, "p.bin").length())
        e.resume(id)
        val s = withTimeout(30_000) { e.await(id) }
        assertEquals(JobState.COMPLETED, s.state)
        assertEquals(0, s.retries)
        assertArrayEquals(data, File(dst, "p.bin").readBytes())
    }

    @Test fun cancelStopsQuickly() = runBlocking {
        val src = tempDir(); val dst = tempDir()
        File(src, "c.bin").writeRandom(2_000_000)
        val e = engine(SlowFs(LocalFs(), "slow", 20), LocalFs())
        val id = e.enqueue(TransferOp.COPY, listOf(Loc("slow", File(src, "c.bin").path)), Loc("local", dst.path))
        withTimeout(10_000) { while (e.jobs.value.first { it.id == id }.doneBytes == 0L) delay(20) }
        val t = System.currentTimeMillis()
        e.cancel(id)
        val s = withTimeout(5_000) { e.await(id) }
        assertEquals(JobState.CANCELLED, s.state)
        assertTrue(System.currentTimeMillis() - t < 3_000)
    }

    @Test fun retryJobResumes() {
        val src = tempDir(); val dst = tempDir()
        val data = File(src, "x.bin").writeRandom(100_000)
        val flaky = FlakyFs(LocalFs(), "f", failOpens = 3, failAfter = 30_000)
        val e = engine(flaky, LocalFs(), cfg = fast.copy(maxRetries = 0))
        val first = run(e, e.enqueue(TransferOp.COPY, listOf(Loc("f", File(src, "x.bin").path)), Loc("local", dst.path)))
        assertEquals(JobState.FAILED, first.state)
        var id = e.retry(first.id)!!
        var s = run(e, id)
        while (s.state == JobState.FAILED) { id = e.retry(id)!!; s = run(e, id) }
        assertEquals(JobState.COMPLETED, s.state)
        assertArrayEquals(data, File(dst, "x.bin").readBytes())
        assertTrue(flaky.readOffsets.last() > 0)
    }
}
