package com.ghostpanter.adb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** adb sync v1 framing (little-endian u32): streaming RECV / SEND over one `sync:` channel. */
internal object SyncWire {
    const val CHUNK = 64 * 1024
    const val MAX_FAIL = 4096

    fun header(tag: String, n: Int): ByteArray {
        val b = ByteArray(8)
        tag.toByteArray(Charsets.US_ASCII).copyInto(b, 0, 0, 4)
        le32(b, 4, n)
        return b
    }

    fun le32(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte(); b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }

    fun readLe32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    fun readFully(input: InputStream, b: ByteArray, off: Int = 0, len: Int = b.size) {
        var n = 0
        while (n < len) {
            val r = input.read(b, off + n, len - n)
            if (r < 0) throw EOFException("sync stream closed")
            n += r
        }
    }

    fun fail(input: InputStream, len: Int): Nothing {
        if (len < 0 || len > MAX_FAIL) throw IOException("sync FAIL length out of range: $len")
        val msg = ByteArray(len)
        readFully(input, msg)
        throw IOException(String(msg, Charsets.UTF_8).trim())
    }

    fun request(ch: AdbChannel, tag: String, path: String) {
        val p = path.toByteArray(Charsets.UTF_8)
        require(p.size in 1..1024 && '\u0000' !in path) { "invalid sync path" }
        ch.output.write(header(tag, p.size))
        ch.output.write(p)
        ch.output.flush()
    }

    /** STAT (v1): returns (mode, size, mtime) or null when the path does not exist. */
    fun stat(ch: AdbChannel, path: String): Triple<Int, Long, Long>? {
        request(ch, "STAT", path)
        val r = ByteArray(16)
        readFully(ch.input, r)
        val tag = String(r, 0, 4, Charsets.US_ASCII)
        if (tag != "STAT") throw IOException("unexpected sync reply $tag")
        val mode = readLe32(r, 4); val size = readLe32(r, 8).toLong() and 0xffffffffL; val mtime = readLe32(r, 12).toLong() and 0xffffffffL
        return if (mode == 0 && size == 0L && mtime == 0L) null else Triple(mode, size, mtime)
    }
}

/** RECV as an InputStream: DATA chunks are handed out as they arrive; closes the channel at the end. */
class SyncRecvInputStream(private val ch: AdbChannel, path: String) : InputStream() {
    private val hdr = ByteArray(8)
    private var remaining = 0
    private var done = false
    private var closed = false

    init { SyncWire.request(ch, "RECV", path) }

    private fun next(): Boolean {
        while (remaining == 0) {
            if (done) return false
            SyncWire.readFully(ch.input, hdr)
            val tag = String(hdr, 0, 4, Charsets.US_ASCII)
            val n = SyncWire.readLe32(hdr, 4)
            when (tag) {
                "DATA" -> { if (n < 0 || n > SyncWire.CHUNK) throw IOException("sync DATA length $n"); remaining = n }
                "DONE" -> { done = true; return false }
                "FAIL" -> SyncWire.fail(ch.input, n)
                else -> throw IOException("unknown sync reply $tag")
            }
        }
        return true
    }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (!next()) return -1
        val r = ch.input.read(b, off, minOf(len, remaining))
        if (r < 0) throw EOFException("sync stream closed mid-file")
        remaining -= r
        return r
    }

    override fun close() {
        if (closed) return
        closed = true
        ch.close()
    }
}

/** SEND as an OutputStream: buffers 64 KiB DATA chunks, DONE + OKAY/FAIL on close. */
class SyncSendOutputStream(
    private val ch: AdbChannel,
    path: String,
    mode: Int = 420, // 0644
    private val mtimeSec: Int = (System.currentTimeMillis() / 1000).toInt(),
    private val onClosed: (() -> Unit)? = null,
) : OutputStream() {
    private val buf = ByteArray(SyncWire.CHUNK)
    private var n = 0
    private var closed = false

    init {
        require(',' !in path) { "sync path may not contain ','" }
        SyncWire.request(ch, "SEND", "$path,$mode")
    }

    override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (closed) throw IOException("closed")
        var o = off; var l = len
        while (l > 0) {
            val k = minOf(l, buf.size - n)
            System.arraycopy(b, o, buf, n, k)
            n += k; o += k; l -= k
            if (n == buf.size) drain()
        }
    }

    private fun drain() {
        if (n == 0) return
        ch.output.write(SyncWire.header("DATA", n))
        ch.output.write(buf, 0, n)
        n = 0
    }

    override fun flush() { drain(); ch.output.flush() }

    override fun close() {
        if (closed) return
        closed = true
        try {
            drain()
            ch.output.write(SyncWire.header("DONE", mtimeSec))
            ch.output.flush()
            val r = ByteArray(8)
            SyncWire.readFully(ch.input, r)
            val tag = String(r, 0, 4, Charsets.US_ASCII)
            when (tag) {
                "OKAY" -> Unit
                "FAIL" -> SyncWire.fail(ch.input, SyncWire.readLe32(r, 4))
                else -> throw IOException("unknown sync reply $tag")
            }
            onClosed?.invoke()
        } finally {
            ch.close()
        }
    }
}
