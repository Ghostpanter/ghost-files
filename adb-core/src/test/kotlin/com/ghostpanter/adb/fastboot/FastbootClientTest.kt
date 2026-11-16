package com.ghostpanter.adb.fastboot

import com.ghostpanter.adb.usb.UsbBulkIo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class FastbootClientTest {

    /** Bidirectional USB bulk fake: host writes land in [toDevice]; [toHost] is what the client reads. */
    private class FakeUsb : UsbBulkIo {
        override val maxPacketSize = 512
        val toDevice = LinkedBlockingQueue<ByteArray>()
        val toHost = LinkedBlockingQueue<ByteArray>()
        @Volatile var closed = false
        private var cur: ByteArray? = null
        private var pos = 0

        private var waitedMs = 0
        override fun bulkIn(b: ByteArray, off: Int, len: Int): Int {
            while (cur == null) {
                if (closed) return -1
                cur = toHost.poll(50, TimeUnit.MILLISECONDS)
                if (cur == null) {
                    waitedMs += 50
                    if (waitedMs > 5_000) throw AssertionError("FakeUsb: no IN data for 5s")
                    continue
                }
                waitedMs = 0
                pos = 0
            }
            val c = cur!!
            val n = minOf(len, c.size - pos)
            System.arraycopy(c, pos, b, off, n)
            pos += n
            if (pos >= c.size) cur = null
            return n
        }

        override fun bulkOut(b: ByteArray, off: Int, len: Int): Int {
            if (closed) return -1
            val slice = b.copyOfRange(off, off + len)
            toDevice.put(slice)
            return len
        }

        override fun close() { closed = true }

        fun respond(status: String, message: String = "") {
            val s = (status + message).toByteArray(Charsets.US_ASCII)
            val pkt = ByteArray(64)
            System.arraycopy(s, 0, pkt, 0, minOf(s.size, 64))
            toHost.put(pkt)
        }

        fun takeCmd(timeoutMs: Long = 2_000): String {
            val b = toDevice.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("no command")
            return b.toString(Charsets.US_ASCII)
        }

        fun takeBytes(expect: Int, timeoutMs: Long = 2_000): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (out.size() < expect) {
                val b = toDevice.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: throw AssertionError("short data ${out.size()} / $expect")
                out.write(b)
            }
            return out.toByteArray()
        }
    }

    private fun withClient(device: (FakeUsb) -> Unit, body: (FastbootClient, FakeUsb) -> Unit) {
        val usb = FakeUsb()
        val t = thread(name = "fake-fastboot") {
            try { device(usb) } catch (_: InterruptedException) {}
        }
        try {
            FastbootClient(usb).use { body(it, usb) }
        } finally {
            usb.closed = true
            t.interrupt()
            t.join(2_000)
        }
    }

    @Test fun getVarOkay() {
        withClient({ usb ->
            assertEquals("getvar:product", usb.takeCmd())
            usb.respond("OKAY", "redfin")
        }) { c, _ ->
            assertEquals("redfin", c.getVar("product"))
        }
    }

    @Test fun getVarMissing() {
        withClient({ usb ->
            assertEquals("getvar:does-not-exist", usb.takeCmd())
            usb.respond("FAIL", "GetVar Variable not found")
        }) { c, _ ->
            assertNull(c.getVar("does-not-exist"))
        }
    }

    @Test fun getVarAllInfoLines() {
        withClient({ usb ->
            assertEquals("getvar:all", usb.takeCmd())
            usb.respond("INFO", "product: redfin")
            usb.respond("INFO", "serialno: ABC123")
            usb.respond("INFO", "unlocked: yes")
            usb.respond("INFO", "is-userspace: no")
            usb.respond("INFO", "current-slot: a")
            usb.respond("INFO", "max-download-size: 0x20000000")
            usb.respond("OKAY")
        }) { c, _ ->
            val info = c.info()
            assertEquals("redfin", info.product)
            assertEquals("ABC123", info.serial)
            assertEquals(true, info.unlocked)
            assertFalse(info.isUserspace)
            assertEquals("a", info.currentSlot)
            assertEquals(0x20000000L, info.maxDownload)
            assertEquals("bootloader", info.mode)
        }
    }

    @Test fun infoFallsBackWhenAllFails() {
        withClient({ usb ->
            assertEquals("getvar:all", usb.takeCmd())
            usb.respond("FAIL", "unknown command")
            val answers = mapOf(
                "getvar:product" to "OKAYoriole",
                "getvar:serialno" to "OKAYSERIAL",
                "getvar:unlocked" to "OKAYyes",
                "getvar:secure" to "FAIL",
                "getvar:current-slot" to "OKAY_b",
                "getvar:slot-count" to "OKAY2",
                "getvar:is-userspace" to "OKAYyes",
                "getvar:max-download-size" to "FAIL",
                "getvar:version" to "OKAY0.4",
                "getvar:version-bootloader" to "FAIL",
                "getvar:hw-revision" to "FAIL",
                "getvar:variant" to "FAIL",
                "getvar:hardware" to "FAIL",
            )
            while (true) {
                val cmd = try { usb.takeCmd(400) } catch (_: AssertionError) { break }
                val r = answers[cmd] ?: "FAIL"
                usb.toHost.put(r.padEnd(64, '\u0000').toByteArray(Charsets.US_ASCII).copyOf(64))
            }
        }) { c, _ ->
            val info = c.info()
            assertEquals("oriole", info.product)
            assertEquals("SERIAL", info.serial)
            assertEquals(true, info.unlocked)
            assertTrue(info.isUserspace)
            assertEquals("b", info.currentSlot)
            assertEquals("fastbootd", info.mode)
        }
    }

    @Test fun downloadAndFlash() {
        val payload = ByteArray(1000) { it.toByte() }
        val tmp = File.createTempFile("fb-flash", ".img").apply { writeBytes(payload) }
        try {
            withClient({ usb ->
                assertEquals("getvar:unlocked", usb.takeCmd())
                usb.respond("OKAY", "yes")
                assertEquals("getvar:max-download-size", usb.takeCmd())
                usb.respond("OKAY", "0x20000000")
                assertEquals("download:%08x".format(payload.size), usb.takeCmd())
                usb.respond("DATA", "%08x".format(payload.size))
                val got = usb.takeBytes(payload.size)
                assertArrayEquals(payload, got)
                usb.respond("OKAY")
                assertEquals("flash:boot", usb.takeCmd())
                usb.respond("INFO", "Writing 'boot'...")
                usb.respond("OKAY")
            }) { c, _ ->
                val r = c.flash("boot", tmp)
                assertTrue(r.ok)
                assertEquals(listOf("Writing 'boot'..."), r.info)
            }
        } finally {
            tmp.delete()
        }
    }

    @Test fun flashRefusedWhenLocked() {
        val tmp = File.createTempFile("fb-locked", ".img").apply { writeBytes(ByteArray(16)) }
        try {
            withClient({ usb ->
                assertEquals("getvar:unlocked", usb.takeCmd())
                usb.respond("OKAY", "no")
            }) { c, _ ->
                try {
                    c.flash("boot", tmp)
                    fail("expected locked refusal")
                } catch (e: FastbootException) {
                    assertTrue(e.message!!.contains("locked"))
                }
            }
        } finally {
            tmp.delete()
        }
    }

    @Test fun flashRefusedWhenUnlockUnknown() {
        val tmp = File.createTempFile("fb-unk", ".img").apply { writeBytes(ByteArray(16)) }
        try {
            withClient({ usb ->
                assertEquals("getvar:unlocked", usb.takeCmd())
                usb.respond("FAIL", "not found")
            }) { c, _ ->
                try {
                    c.erase("userdata")
                    fail("expected refusal")
                } catch (e: FastbootException) {
                    assertTrue(e.message!!.contains("locked"))
                }
            }
        } finally {
            tmp.delete()
        }
    }

    @Test fun rebootAndSetActive() {
        withClient({ usb ->
            assertEquals("reboot-bootloader", usb.takeCmd())
            usb.respond("OKAY")
            assertEquals("getvar:unlocked", usb.takeCmd())
            usb.respond("OKAY", "yes")
            assertEquals("set_active:b", usb.takeCmd())
            usb.respond("OKAY")
        }) { c, _ ->
            assertTrue(c.reboot(FastbootClient.RebootTarget.BOOTLOADER).ok)
            assertTrue(c.setActive("B").ok)
        }
    }

    @Test fun failStatusSurfacesMessage() {
        withClient({ usb ->
            assertEquals("getvar:unlocked", usb.takeCmd())
            usb.respond("OKAY", "yes")
            assertEquals("erase:boot", usb.takeCmd())
            usb.respond("FAIL", "partition not found")
        }) { c, _ ->
            try {
                c.erase("boot")
                fail("expected fail")
            } catch (e: FastbootException) {
                assertTrue(e.message!!.contains("partition not found"))
            }
        }
    }

    @Test fun parseHelpers() {
        assertEquals(0x20000000L, FastbootClient.parseSize("0x20000000"))
        assertEquals(512L, FastbootClient.parseSize("512"))
        assertEquals(0L, FastbootClient.parseSize("bogus"))
        assertEquals(0x00001000, FastbootClient.parseHex8("00001000"))
        assertEquals(true, FastbootClient.parseUnlocked("yes", null))
        assertEquals(false, FastbootClient.parseUnlocked("no", null))
        assertEquals(false, FastbootClient.parseUnlocked(null, "yes"))
        assertEquals(true, FastbootClient.parseUnlocked(null, "no"))
        assertNull(FastbootClient.parseUnlocked(null, null))
        val vars = FastbootClient.parseVars(listOf("product: redfin", "unlocked:yes", "", "no-colon"))
        assertEquals("redfin", vars["product"])
        assertEquals("yes", vars["unlocked"])
        assertFalse(vars.containsKey("no-colon"))
    }

    @Test fun rejectBadPartitionName() {
        try {
            FastbootClient.requirePartition("boot;reboot")
            fail()
        } catch (e: FastbootException) {
            assertTrue(e.message!!.contains("invalid partition"))
        }
    }

    @Test fun imageLargerThanMaxIsRejectedForRaw() {
        val tmp = File.createTempFile("fb-big", ".img").apply { writeBytes(ByteArray(64)) }
        try {
            withClient({ usb ->
                assertEquals("getvar:unlocked", usb.takeCmd())
                usb.respond("OKAY", "yes")
                assertEquals("getvar:max-download-size", usb.takeCmd())
                usb.respond("OKAY", "16")
            }) { c, _ ->
                try {
                    c.flash("system", tmp)
                    fail("expected size error")
                } catch (e: FastbootException) {
                    assertTrue(e.message!!.contains("max-download-size"))
                }
            }
        } finally {
            tmp.delete()
        }
    }

    @Test fun sparseSplitFlashesEachPart() {
        // 3 RAW blocks of 4 bytes; max-download fits header+one raw chunk only (28+12+4=44, two would be 60, use 50).
        val blk = 4
        val blocks = 3
        val image = buildSparse(blk, List(blocks) { ByteArray(blk) { it.toByte() } })
        val tmp = File.createTempFile("fb-sparse", ".img").apply { writeBytes(image) }
        val dir = File(tmp.parentFile, "fb-parts-${System.nanoTime()}").also { it.mkdirs() }
        try {
            val parts = mutableListOf<ByteArray>()
            withClient({ usb ->
                assertEquals("getvar:unlocked", usb.takeCmd())
                usb.respond("OKAY", "yes")
                assertEquals("getvar:max-download-size", usb.takeCmd())
                usb.respond("OKAY", "50")
                repeat(blocks) {
                    val cmd = usb.takeCmd()
                    assertTrue(cmd.startsWith("download:"))
                    val sz = cmd.substringAfter(":").toLong(16).toInt()
                    usb.respond("DATA", "%08x".format(sz))
                    parts += usb.takeBytes(sz)
                    usb.respond("OKAY")
                    assertEquals("flash:system", usb.takeCmd())
                    usb.respond("OKAY")
                }
            }) { c, _ ->
                val r = c.flash("system", tmp, tmpDir = dir)
                assertTrue(r.ok)
            }
            assertEquals(blocks, parts.size)
            for (p in parts) {
                val h = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
                assertEquals(AndroidSparse.MAGIC, h.int)
                h.short; h.short; h.short; h.short
                assertEquals(blk, h.int)
                assertEquals(1, h.int) // one block per part
                assertEquals(1, h.int) // one chunk
            }
        } finally {
            tmp.delete()
            dir.deleteRecursively()
        }
    }
}

private fun buildSparse(blk: Int, rawBlocks: List<ByteArray>): ByteArray {
    val chunks = rawBlocks.size
    val payload = rawBlocks.sumOf { it.size }
    val out = ByteBuffer.allocate(AndroidSparse.HEADER + chunks * AndroidSparse.CHUNK_HDR + payload)
        .order(ByteOrder.LITTLE_ENDIAN)
    out.putInt(AndroidSparse.MAGIC).putShort(1).putShort(0)
        .putShort(AndroidSparse.HEADER.toShort()).putShort(AndroidSparse.CHUNK_HDR.toShort())
        .putInt(blk).putInt(rawBlocks.size).putInt(chunks).putInt(0)
    for (b in rawBlocks) {
        out.putShort(AndroidSparse.RAW.toShort()).putShort(0)
            .putInt(1).putInt(AndroidSparse.CHUNK_HDR + b.size)
        out.put(b)
    }
    return out.array()
}
