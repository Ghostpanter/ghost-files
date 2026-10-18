package com.ghostpanter.files.core

import com.ghostpanter.files.core.transfer.JobState
import com.ghostpanter.files.core.transfer.Loc
import com.ghostpanter.files.core.transfer.TransferConfig
import com.ghostpanter.files.core.transfer.TransferEngine
import com.ghostpanter.files.core.transfer.TransferOp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import kotlin.random.Random

/** Shared behaviour every backend must satisfy. [root] is a writable directory on [fs]. */
object FsContract {
    fun run(fs: VirtualFs, root: String) {
        val dir = VPath.join(root, "合同 test")
        fs.mkdirs(VPath.join(dir, "sub/deep"))
        assertTrue(fs.stat(dir)!!.isDirectory)

        val data = Random(3).nextBytes(150_000)
        val f = VPath.join(dir, "file one.bin")
        fs.openWrite(f, 0, data.size.toLong()).use { it.write(data) }
        val st = fs.stat(f)
        assertNotNull(st)
        assertEquals(data.size.toLong(), st!!.size)
        assertFalse(st.isDirectory)
        assertArrayEquals(data, fs.openRead(f).use { it.readBytes() })

        if (fs.has(Capability.RESUME_READ)) {
            assertArrayEquals(data.copyOfRange(100_000, data.size), fs.openRead(f, 100_000).use { it.readBytes() })
        }
        if (fs.has(Capability.RESUME_WRITE)) {
            val g = VPath.join(dir, "resume.bin")
            fs.openWrite(g).use { it.write(data, 0, 60_000) }
            fs.openWrite(g, 60_000).use { it.write(data, 60_000, data.size - 60_000) }
            assertArrayEquals(data, fs.openRead(g).use { it.readBytes() })
            fs.delete(g, false)
        }

        val names = fs.list(dir).map { it.name }.toSet()
        assertEquals(setOf("sub", "file one.bin"), names)
        assertTrue(fs.list(dir).first { it.name == "sub" }.isDirectory)

        val moved = VPath.join(dir, "sub/renamed.bin")
        fs.rename(f, moved)
        assertNull(fs.stat(f))
        assertEquals(data.size.toLong(), fs.stat(moved)!!.size)

        if (fs.has(Capability.SERVER_COPY)) {
            val c = VPath.join(dir, "copy.bin")
            if (fs.serverCopy(moved, c)) assertArrayEquals(data, fs.openRead(c).use { it.readBytes() })
        }

        // Round-trip through the transfer engine: local -> fs -> local.
        val localSrc = tempDir(); val localDst = tempDir()
        val a = File(localSrc, "up/a.bin").writeRandom(300_000, 11)
        val b = File(localSrc, "up/n/中文.txt").writeRandom(1234, 12)
        val local = LocalFs()
        val engine = TransferEngine({ if (it == fs.id) fs else local }, TransferConfig(initialBackoffMs = 10))
        runBlocking {
            withTimeout(60_000) {
                val up = engine.await(engine.enqueue(TransferOp.COPY, listOf(Loc(local.id, File(localSrc, "up").path)), Loc(fs.id, dir)))
                assertEquals(up.failures.toString(), JobState.COMPLETED, up.state)
                val down = engine.await(engine.enqueue(TransferOp.MOVE, listOf(Loc(fs.id, VPath.join(dir, "up"))), Loc(local.id, localDst.path)))
                assertEquals(down.failures.toString(), JobState.COMPLETED, down.state)
            }
        }
        assertArrayEquals(a, File(localDst, "up/a.bin").readBytes())
        assertArrayEquals(b, File(localDst, "up/n/中文.txt").readBytes())
        assertNull(fs.stat(VPath.join(dir, "up")))

        fs.delete(dir, true)
        assertNull(fs.stat(dir))
    }
}
