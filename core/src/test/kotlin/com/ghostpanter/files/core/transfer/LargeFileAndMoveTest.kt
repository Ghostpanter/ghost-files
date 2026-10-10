package com.ghostpanter.files.core.transfer

import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Synthetic backend for multi-GB files without disk or RAM: file content is a function of the
 * position, written files keep only their length and a running checksum.
 */
private class SyntheticFs(override val id: String, private val remote: Boolean = true) : VirtualFs {
    override val displayName = id
    override val capabilities = buildSet {
        add(Capability.RESUME_READ); add(Capability.RESUME_WRITE)
        if (remote) add(Capability.REMOTE)
    }

    class Node(@Volatile var size: Long, @Volatile var sum: Long = 0)
    val files = ConcurrentHashMap<String, Node>()
    val readOffsets = CopyOnWriteArrayList<Long>()
    val deleteAttempts = AtomicInteger()
    @Volatile var deleteError: String? = null
    /** Throw from the output's close() this many times (after all bytes were written). */
    val failCloses = AtomicInteger(0)

    fun put(path: String, size: Long) { files[VPath.normalize(path)] = Node(size, expectedSum(0, size)) }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?) =
        files.keys.filter { VPath.parent(it) == VPath.normalize(path) }.map { stat(it)!! }

    override fun stat(path: String): FileEntry? {
        val n = VPath.normalize(path)
        if (n == "/" || files.keys.any { it.startsWith("$n/") }) return FileEntry(n, VPath.name(n), true, 0, 0)
        val f = files[n] ?: return null
        return FileEntry(n, VPath.name(n), false, f.size, 1_000)
    }

    override fun openRead(path: String, offset: Long): InputStream {
        readOffsets.add(offset)
        val size = files[VPath.normalize(path)]?.size ?: throw FsException("不存在")
        return object : InputStream() {
            var pos = offset
            override fun read(): Int = if (pos >= size) -1 else byteAt(pos++)
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= size) return -1
                val n = minOf(len.toLong(), size - pos).toInt()
                for (i in 0 until n) b[off + i] = byteAt(pos + i).toByte()
                pos += n
                return n
            }
        }
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream {
        val p = VPath.normalize(path)
        val node = if (offset > 0) files[p]!!.also { require(it.size >= offset); it.size = offset; it.sum = expectedSum(0, offset) }
        else Node(0).also { files[p] = it }
        return object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var s = node.sum
                for (i in 0 until len) s += (b[off + i].toInt() and 0xFF)
                node.sum = s
                node.size += len
            }
            override fun close() {
                if (failCloses.get() > 0 && failCloses.decrementAndGet() >= 0) throw IOException("simulated close failure")
            }
        }
    }

    override fun mkdirs(path: String) {}
    override fun delete(path: String, recursive: Boolean) {
        deleteAttempts.incrementAndGet()
        deleteError?.let { throw FsException("删除 $path: $it") }
        files.remove(VPath.normalize(path))
    }
    override fun rename(from: String, to: String) { files[VPath.normalize(to)] = files.remove(VPath.normalize(from))!! }

    companion object {
        fun byteAt(pos: Long): Int = ((pos * 31 + (pos ushr 20)) % 251).toInt()
        /** Sum of all bytes in [from, to): computed in blocks of 251 for speed. */
        fun expectedSum(from: Long, to: Long): Long {
            var s = 0L
            var p = from
            while (p < to) { s += byteAt(p); p++ }
            return s
        }
    }
}

class LargeFileAndMoveTest {
    private val cfg = TransferConfig(initialBackoffMs = 10, maxBackoffMs = 20, bufferSize = 1 shl 20, stallTimeoutMs = 30_000)

    private fun engine(a: VirtualFs, b: VirtualFs) = TransferEngine({ if (it == a.id) a else b }, cfg)
    private fun await(e: TransferEngine, id: Long) = runBlocking { withTimeout(300_000) { e.await(id) } }

    /** The device report: a > 2 GiB file SMB → local reached 100 % and then kept retrying. */
    @Test fun movesFileLargerThan2GiBWithoutRetries() {
        val size = (1L shl 31) + 7_777_777 // 2 GiB + change: every Int-typed size/offset would overflow
        val src = SyntheticFs("smb")
        val dst = SyntheticFs("local", remote = false)
        src.put("/share/video.mp4", size)
        val expected = src.files["/share/video.mp4"]!!.sum
        val e = engine(src, dst)
        val s = await(e, e.enqueue(TransferOp.MOVE, listOf(Loc("smb", "/share/video.mp4")), Loc("local", "/dl")))
        assertEquals(s.toString(), JobState.COMPLETED, s.state)
        assertEquals(0, s.retries)
        assertEquals(size, s.totalBytes)
        assertEquals(size, s.doneBytes)
        assertEquals(1, s.doneFiles)
        assertEquals(size, dst.files["/dl/video.mp4"]!!.size)
        assertEquals(expected, dst.files["/dl/video.mp4"]!!.sum)
        assertTrue(src.files.isEmpty())
    }

    /** Resume where the destination already holds every byte: no read past EOF, no re-copy. */
    @Test fun resumeWithOffsetEqualToSizeFinalizesWithoutCopying() {
        val size = (1L shl 31) + 5
        val src = SyntheticFs("smb"); val dst = SyntheticFs("local", remote = false)
        src.put("/s/a.bin", size); dst.put("/d/a.bin", size)
        val e = engine(src, dst)
        val s = await(e, e.enqueue(TransferOp.MOVE, listOf(Loc("smb", "/s/a.bin")), Loc("local", "/d"), ConflictPolicy.RESUME))
        assertEquals(JobState.COMPLETED, s.state)
        assertEquals(0, s.retries)
        assertTrue("must not read at all", src.readOffsets.isEmpty())
        assertTrue(src.files.isEmpty())
    }

    /** A partially written > 2 GiB file continues at a Long offset beyond Int.MAX_VALUE. */
    @Test fun resumesBeyondIntRange() {
        val size = (1L shl 31) + 300_000_000L
        val partial = (1L shl 31) + 1_000
        val src = SyntheticFs("smb"); val dst = SyntheticFs("local", remote = false)
        src.put("/s/b.bin", size); dst.put("/d/b.bin", partial)
        val e = engine(src, dst)
        val s = await(e, e.enqueue(TransferOp.COPY, listOf(Loc("smb", "/s/b.bin")), Loc("local", "/d"), ConflictPolicy.RESUME))
        assertEquals(JobState.COMPLETED, s.state)
        assertEquals(listOf(partial), src.readOffsets.toList())
        assertEquals(size, dst.files["/d/b.bin"]!!.size)
        assertEquals(src.files["/s/b.bin"]!!.sum, dst.files["/d/b.bin"]!!.sum)
    }

    /** All bytes written but close() failed: the retry must finalize instead of copying/reading at EOF again. */
    @Test fun failureAfterLastByteDoesNotRecopy() {
        val src = SyntheticFs("smb"); val dst = SyntheticFs("local", remote = false)
        src.put("/s/c.bin", 3_000_000)
        dst.failCloses.set(1)
        val e = engine(src, dst)
        val s = await(e, e.enqueue(TransferOp.MOVE, listOf(Loc("smb", "/s/c.bin")), Loc("local", "/d")))
        assertEquals(s.toString(), JobState.COMPLETED, s.state)
        assertEquals(1, s.retries)
        assertEquals(listOf(0L), src.readOffsets.toList())
        assertTrue(s.lastError!!.contains("simulated close failure"))
        assertTrue(src.files.isEmpty())
    }

    /** MOVE whose source delete fails: data stays copied, the copy is not repeated, the reason is reported. */
    @Test fun moveWithFailingSourceDeleteKeepsCopyAndReports() {
        val src = SyntheticFs("smb"); val dst = SyntheticFs("local", remote = false)
        src.put("/s/one.bin", 1_000_000); src.put("/s/two.bin", 2_000_000)
        src.deleteError = "文件正被其他程序占用"
        val e = engine(src, dst)
        val s = await(e, e.enqueue(TransferOp.MOVE, listOf(Loc("smb", "/s/one.bin"), Loc("smb", "/s/two.bin")), Loc("local", "/d")))
        assertEquals(JobState.FAILED, s.state)
        assertEquals(2, s.doneFiles) // both copied
        assertEquals(2, s.keptSources)
        assertEquals(1_000_000L, dst.files["/d/one.bin"]!!.size)
        assertEquals(2_000_000L, dst.files["/d/two.bin"]!!.size)
        assertEquals("each file read exactly once", 2, src.readOffsets.size)
        assertEquals("delete retried at most 2 extra times per file", 6, src.deleteAttempts.get())
        assertTrue(s.failures.all { it.contains("已复制，但删除源文件失败") && it.contains("占用") })
        assertTrue(s.lastError!!.contains("删除源文件失败"))
        assertEquals(2, src.files.size) // sources kept
    }
}
