package com.ghostpanter.adb

import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FsContract
import com.ghostpanter.files.core.tempDir
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.random.Random

class AdbFsTest {
    private val fake = FakeAdb()
    private val device = AdbDevice("127.0.0.1:5555") { fake }

    @Test fun shellExitCodesAndOutput() {
        val r = device.sh("echo hi; echo err >&2; exit 3")
        assertEquals(3, r.code)
        assertEquals("hi\nerr\n", r.stdout)
        assertEquals(0, device.sh("true").code)
        assertEquals("no-newline", device.sh("printf no-newline").stdout)
        assertEquals("a\n\n", device.sh("printf 'a\\n\\n'").stdout)
        assertEquals("x", device.text("printf x"))
    }

    @Test fun longScriptGoesThroughStdin() {
        val long = "echo " + "y".repeat(5000) + "; exit 4"
        val r = device.sh(long)
        assertEquals(4, r.code)
        assertEquals(5001, r.stdout.length)
        assertTrue(fake.destinations.any { it == "shell:sh" })
    }

    @Test fun binaryOutputIsUntouched() {
        val d = tempDir(); val data = Random(5).nextBytes(400_000)
        val f = File(d, "b.bin"); f.writeBytes(data)
        val p = device.runner().start("exec cat -- '${f.path}'")
        val got = p.inputStream.readBytes()
        assertArrayEquals(data, got)
        assertEquals(0, p.waitFor())
    }

    @Test fun sentinelSplitAcrossReads() {
        val marker = "__GFRC_abc:"
        val payload = "hello\n" + "z".repeat(10_000)
        val wire = (payload + marker + "7\n").toByteArray()
        // one byte at a time
        val trickle = object : InputStream() {
            var i = 0
            override fun read(): Int = if (i < wire.size) wire[i++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int { if (len == 0) return 0; val c = read(); if (c < 0) return -1; b[off] = c.toByte(); return 1 }
        }
        var code = -1
        val s = SentinelInputStream(trickle, marker.toByteArray()) { code = it }
        assertEquals(payload, s.readBytes().toString(Charsets.UTF_8))
        assertEquals(7, code)
        // EOF without marker -> 255
        var c2 = 0
        assertEquals("abc", SentinelInputStream(ByteArrayInputStream("abc".toByteArray()), marker.toByteArray()) { c2 = it }.readBytes().toString(Charsets.UTF_8))
        assertEquals(255, c2)
    }

    @Test fun syncPushPullAndStat() {
        val d = tempDir(); val data = Random(9).nextBytes(300_001)
        val f = File(d, "s.bin").path
        assertEquals(data.size.toLong(), device.push(ByteArrayInputStream(data), f))
        val out = ByteArrayOutputStream(); device.pull(f, out)
        assertArrayEquals(data, out.toByteArray())
        val st = device.statSync(f)!!
        assertEquals(data.size.toLong(), st.second)
        assertEquals(null, device.statSync(File(d, "missing").path))
        val err = runCatching { device.pull(File(d, "missing").path, ByteArrayOutputStream()) }.exceptionOrNull()
        assertTrue(err?.message, err?.message?.contains("No such file") == true)
    }

    @Test fun adbFsContract() {
        val fs = AdbFs(device, "adb:127.0.0.1:5555", "fake")
        assertFalse(fs.has(Capability.RESUME_WRITE))
        assertTrue(fs.has(Capability.RESUME_READ))
        FsContract.run(fs, tempDir().path)
        // whole-file reads and writes went through sync
        assertTrue(fake.destinations.count { it == "sync:" } >= 2)
        fs.close()
    }

    @Test fun readAtForThumbnails() {
        val d = tempDir(); val data = ByteArray(2_000_000) { (it * 31 + it / 4093).toByte() }
        val f = File(d, "v.mp4"); f.writeBytes(data)
        val fs = AdbFs(device, "adb:x", "fake")
        for (off in listOf(0L, 4095L, 1_234_567L, data.size - 100L)) {
            val buf = ByteArray(5000)
            val n = fs.readAt(f.path, off, buf, 0, buf.size)
            val want = minOf(5000L, data.size - off).toInt()
            assertEquals(want, n)
            assertArrayEquals(data.copyOfRange(off.toInt(), off.toInt() + want), buf.copyOf(n))
        }
        assertEquals(-1, fs.readAt(f.path, data.size.toLong() + 10, ByteArray(10), 0, 10))
        fs.close()
    }

    @Test fun reconnectsOnceAfterBrokenTransport() {
        var t = FakeAdb()
        val dev = AdbDevice("1.2.3.4:5555") { t }
        assertEquals(0, dev.sh("true").code)
        t.broken = true
        AdbPool.factory = null
        // provider hands out a fresh fake after invalidation
        val fresh = FakeAdb()
        val dev2 = AdbDevice("1.2.3.4:5555") { if (t.broken) { t = fresh; fresh } else t }
        assertEquals("ok", dev2.text("printf ok"))
    }

    @Test fun syncListDirectory() {
        val d = tempDir()
        File(d, "a.txt").writeText("hi")
        File(d, "sub").mkdir()
        val entries = device.listSync(d.path)
        val names = entries.map { it.name }.toSet()
        assertTrue(names.contains("a.txt"))
        assertTrue(names.contains("sub"))
        val sub = entries.first { it.name == "sub" }
        assertTrue(SyncWire.isDir(sub.mode))
        val file = entries.first { it.name == "a.txt" }
        assertTrue(SyncWire.isReg(file.mode))
        assertEquals(2L, file.size)
    }

    @Test fun adbFsListPrefersSync() {
        val d = tempDir()
        File(d, "x.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(d, "dir").mkdir()
        val fs = AdbFs(device, "adb:list-test", "fake")
        fake.destinations.clear()
        val listed = fs.list(d.path)
        assertEquals(2, listed.size)
        assertTrue(listed.any { it.name == "x.bin" && !it.isDirectory && it.size == 3L })
        assertTrue(listed.any { it.name == "dir" && it.isDirectory })
        // browse went through sync:, not a long shell find
        assertTrue(fake.destinations.any { it == "sync:" })
        fs.close()
    }

    @Test fun multilineShellGoesThroughStdin() {
        fake.destinations.clear()
        val script = """
            echo one
            echo two
            exit 5
        """.trimIndent()
        val r = device.sh(script)
        assertEquals(5, r.code)
        assertTrue(r.stdout.contains("one"))
        assertTrue(r.stdout.contains("two"))
        assertTrue(fake.destinations.any { it == "shell:sh" })
    }

}
