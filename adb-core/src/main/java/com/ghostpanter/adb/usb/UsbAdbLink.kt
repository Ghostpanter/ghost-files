package com.ghostpanter.adb.usb

import io.github.muntashirakon.adb.AdbLink
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

/** The two bulk endpoints of an ADB USB interface. Android: [AndroidUsbBulk]; tests: a fake device. */
interface UsbBulkIo : Closeable {
    /** wMaxPacketSize of the OUT endpoint (512 on USB 2.0 high speed, 1024 on USB 3). */
    val maxPacketSize: Int
    /** One IN transfer into b[off, off+len). Returns the bytes received (0 = zero-length packet), -1 on error. */
    fun bulkIn(b: ByteArray, off: Int, len: Int): Int
    /** One OUT transfer. len = 0 sends a zero-length packet. Returns bytes sent, -1 on error. */
    fun bulkOut(b: ByteArray, off: Int, len: Int): Int
}

/**
 * ADB framing over USB bulk endpoints (same rules as the platform's `adb` host):
 *  - every message goes out as a 24-byte header transfer, then the payload in its own transfer(s);
 *  - a payload that is an exact multiple of wMaxPacketSize is terminated by a zero-length packet,
 *    otherwise adbd's FunctionFS read would wait for more data;
 *  - reads request exact lengths (header, then payload), and skip zero-length packets.
 */
class UsbAdbLink(private val io: UsbBulkIo) : AdbLink {
    @Volatile private var closed = false
    private val writeLock = Any()

    private val input = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            var zeros = 0
            while (true) {
                if (closed) return -1
                val n = io.bulkIn(b, off, minOf(len, CHUNK))
                if (n > 0) return n
                if (n == 0) {
                    if (++zeros > MAX_ZLP_IN_A_ROW) throw IOException("USB: endless zero-length packets")
                    continue
                }
                if (closed) return -1
                throw IOException("USB bulk read failed (device detached?)")
            }
        }
    }

    override fun getInputStream(): InputStream = input

    override fun writePacket(packet: ByteArray) {
        if (packet.size < HEADER) throw IOException("short ADB packet")
        synchronized(writeLock) {
            if (closed) throw IOException("USB link closed")
            out(packet, 0, HEADER)
            val payload = packet.size - HEADER
            var off = HEADER
            while (off < packet.size) {
                val n = minOf(CHUNK, packet.size - off)
                out(packet, off, n)
                off += n
            }
            val mps = io.maxPacketSize
            if (payload > 0 && mps > 0 && payload % mps == 0) {
                if (io.bulkOut(packet, 0, 0) < 0) throw IOException("USB zero-length packet failed")
            }
        }
    }

    private fun out(b: ByteArray, off: Int, len: Int) {
        var o = off
        val end = off + len
        while (o < end) {
            val w = io.bulkOut(b, o, end - o)
            if (w <= 0) throw IOException("USB bulk write failed (device detached?)")
            o += w
        }
    }

    override fun isClosed(): Boolean = closed

    override fun close() {
        if (closed) return
        closed = true
        runCatching { io.close() }
    }

    companion object {
        const val HEADER = 24
        /** Transfer size; a multiple of every bulk wMaxPacketSize (64 / 512 / 1024). */
        const val CHUNK = 16 * 1024
        private const val MAX_ZLP_IN_A_ROW = 64
    }
}
