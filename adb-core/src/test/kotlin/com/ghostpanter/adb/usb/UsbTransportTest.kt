package com.ghostpanter.adb.usb

import com.ghostpanter.adb.LibAdbTransport
import io.github.muntashirakon.adb.AdbConnection
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.security.cert.Certificate
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * ADB over USB bulk endpoints against a fake adbd that enforces USB framing:
 * header and payload in separate transfers, ZLP after payloads that are a multiple of wMaxPacketSize.
 */
/** ADB wire constants (libadb keeps AdbProtocol package-private). */
private object P {
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val ADB_AUTH_TOKEN = 1
    const val ADB_AUTH_SIGNATURE = 2
    const val ADB_AUTH_RSAPUBLICKEY = 3
    fun message(cmd: Int, a0: Int, a1: Int, data: ByteArray?): ByteArray {
        val d = data ?: ByteArray(0)
        val b = ByteBuffer.allocate(24 + d.size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(d.size).putInt(d.sumOf { it.toInt() and 0xff }).putInt(cmd xor -1)
        b.put(d)
        return b.array()
    }
}

class UsbTransportTest {

    private class FakeCert(private val key: PublicKey) : Certificate("X.509") {
        override fun getEncoded() = ByteArray(0)
        override fun verify(key: PublicKey?) {}
        override fun verify(key: PublicKey?, sigProvider: String?) {}
        override fun toString() = "fake"
        override fun getPublicKey() = key
    }

    /** Fake USB device + adbd. */
    private class FakeDevice(val acceptSignature: Boolean, override val maxPacketSize: Int = 512) : UsbBulkIo {
        val toDevice = LinkedBlockingQueue<ByteArray>()
        val toHost = LinkedBlockingQueue<ByteArray>()
        val violations = CopyOnWriteArrayList<String>()
        val received = CopyOnWriteArrayList<Pair<Int, ByteArray>>()  // command, payload
        val zlpAfter = CopyOnWriteArrayList<Int>()                     // payload sizes followed by a ZLP
        var publicKeyOffered = false
        private val pending = ArrayDeque<() -> Unit>()
        @Volatile var closed = false
        private var cur: ByteArray? = null
        private var pos = 0

        override fun bulkIn(b: ByteArray, off: Int, len: Int): Int {
            while (cur == null) {
                if (closed) return -1
                cur = toHost.poll(50, TimeUnit.MILLISECONDS)
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
            // Model a host controller that may accept less than asked for large transfers.
            val n = if (len > 8192) 8192 else len
            toDevice.put(b.copyOfRange(off, off + n))
            return n
        }

        override fun close() { closed = true }

        private fun take(): ByteArray? {
            while (!closed) toDevice.poll(50, TimeUnit.MILLISECONDS)?.let { return it }
            return null
        }

        private fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray?) {
            val m = P.message(cmd, a0, a1, data)
            toHost.put(m.copyOfRange(0, 24))
            if (data != null && data.isNotEmpty()) {
                toHost.put(m.copyOfRange(24, m.size))
                if (data.size % maxPacketSize == 0) toHost.put(ByteArray(0))
            }
        }

        fun run() = thread(isDaemon = true, name = "fake-adbd") {
            while (!closed) {
                val h = take() ?: return@thread
                if (h.isEmpty()) continue  // stray ZLP
                if (h.size != 24) { violations += "header transfer of ${h.size} bytes"; continue }
                val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
                val cmd = bb.int; val a0 = bb.int; val a1 = bb.int; val len = bb.int
                val out = ByteArrayOutputStream()
                while (out.size() < len) {
                    val t = take() ?: return@thread
                    if (t.isEmpty()) { violations += "ZLP inside payload"; continue }
                    out.write(t)
                }
                if (out.size() != len) violations += "payload overrun ${out.size()} > $len"
                val payload = out.toByteArray()
                if (len > 0 && len % maxPacketSize == 0) {
                    val z = take() ?: return@thread
                    if (z.isNotEmpty()) violations += "missing ZLP after $len-byte payload" else zlpAfter += len
                }
                received += cmd to payload
                when (cmd) {
                    P.A_CNXN -> send(P.A_AUTH, P.ADB_AUTH_TOKEN, 0, ByteArray(20) { it.toByte() })
                    P.A_AUTH -> when (a0) {
                        P.ADB_AUTH_SIGNATURE ->
                            if (acceptSignature) cnxn() else send(P.A_AUTH, P.ADB_AUTH_TOKEN, 0, ByteArray(20) { (it + 1).toByte() })
                        P.ADB_AUTH_RSAPUBLICKEY -> { publicKeyOffered = true; cnxn() }
                    }
                    P.A_OPEN -> {
                        val dest = String(payload, Charsets.UTF_8).trimEnd('\u0000')
                        send(P.A_OKAY, REMOTE, a0, null)
                        if (dest.startsWith("shell:")) {
                            // Like adbd: one WRTE in flight, the next only after the host's OKAY.
                            pending.add { send(P.A_WRTE, REMOTE, a0, "hi\n".toByteArray()) }
                            pending.add { send(P.A_CLSE, REMOTE, a0, null) }
                            send(P.A_WRTE, REMOTE, a0, ByteArray(512) { 'x'.code.toByte() })
                        }
                    }
                    P.A_OKAY -> pending.removeFirstOrNull()?.invoke()
                    P.A_WRTE -> send(P.A_OKAY, REMOTE, a0, null)
                }
            }
        }

        private fun cnxn() = send(P.A_CNXN, 0x01000001, 256 * 1024, "device::ro.product.model=Fake;".toByteArray())

        companion object { const val REMOTE = 77 }
    }

    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val conns = mutableListOf<AdbConnection>()

    @After fun tearDown() { conns.forEach { runCatching { it.close() } } }

    private fun connect(dev: FakeDevice): AdbConnection {
        dev.run()
        val c = AdbConnection.overLink(UsbAdbLink(dev), keys.private, FakeCert(keys.public))
        conns += c
        assertTrue("CNXN within timeout", c.connect(5, TimeUnit.SECONDS))
        return c
    }

    @Test fun unknownKeyOffersPublicKeyThenShellStreamsOverUsb() {
        val dev = FakeDevice(acceptSignature = false)
        val c = connect(dev)
        assertTrue(dev.publicKeyOffered)
        assertTrue(c.isLegacyAuth)
        val t = LibAdbTransport(c, owned = false)
        val ch = t.open("shell:echo hi")
        val text = ch.input.readBytes()
        ch.close()
        assertEquals(515, text.size)
        assertEquals("hi\n", String(text, 512, 3))
        assertTrue(dev.violations.toString(), dev.violations.isEmpty())
    }

    @Test fun knownKeySignatureAndExactPacketWriteSendsZlp() {
        val dev = FakeDevice(acceptSignature = true)
        val c = connect(dev)
        assertTrue(!dev.publicKeyOffered)
        val ch = LibAdbTransport(c, owned = false).open("sink:")
        val block = ByteArray(1024) { (it % 251).toByte() }
        ch.output.write(block)          // 1024 = 2 x wMaxPacketSize -> must end with a ZLP
        ch.output.write(ByteArray(100)) // not a multiple -> no ZLP
        ch.output.flush()
        val deadline = System.currentTimeMillis() + 5000
        while (dev.received.count { it.first == P.A_WRTE } < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val writes = dev.received.filter { it.first == P.A_WRTE }.map { it.second }
        assertEquals(2, writes.size)
        assertArrayEquals(block, writes[0])
        assertEquals(listOf(1024), dev.zlpAfter.filter { it == 1024 })
        assertTrue(dev.violations.toString(), dev.violations.isEmpty())
        ch.close()
    }

    @Test fun largeWriteSplitsIntoChunksAndHeaderStaysAlone() {
        val dev = FakeDevice(acceptSignature = true, maxPacketSize = 1024)
        val c = connect(dev)
        val ch = LibAdbTransport(c, owned = false).open("sink:")
        val big = ByteArray(200_000) { (it * 7).toByte() }
        ch.output.write(big)
        ch.output.flush()
        val deadline = System.currentTimeMillis() + 5000
        fun got() = dev.received.filter { it.first == P.A_WRTE }.sumOf { it.second.size }
        while (got() < big.size && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(big.size, got())
        val joined = dev.received.filter { it.first == P.A_WRTE }.map { it.second }.reduce { a, b -> a + b }
        assertArrayEquals(big, joined)
        assertTrue(dev.violations.toString(), dev.violations.isEmpty())
        ch.close()
    }

    @Test fun closedLinkEndsReads() {
        val dev = FakeDevice(acceptSignature = true)
        val link = UsbAdbLink(dev)
        link.close()
        assertEquals(-1, link.inputStream.read(ByteArray(24), 0, 24))
        assertTrue(dev.closed)
    }

    @Test fun adbInterfaceTriple() {
        assertTrue(UsbAdb.isAdbInterface(0xFF, 0x42, 0x01))
        assertTrue(!UsbAdb.isAdbInterface(0xFF, 0x42, 0x03)) // fastboot is 0x42/0x03: never touched
        assertEquals("usb-ABC123:1", UsbAdb.serialOfKey("ABC123"))
        assertEquals("ABC123", UsbAdb.keyOfSerial("usb-ABC123:1"))
        assertEquals("a_b", UsbAdb.sanitize("a b"))
        assertTrue(UsbAdb.isUsbSerial("usb-x:1") && !UsbAdb.isUsbSerial("192.168.1.2:5555"))
    }
}
