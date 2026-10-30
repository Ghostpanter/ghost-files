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

    /**
     * LIST (v1): directory entries as (name, mode, size, mtimeSec). Skips "." / "..".
     * Same adbd channel as RECV/SEND — use this for browsing when shell find+stat is empty
     * or unreliable on OEM builds.
     */
    fun list(ch: AdbChannel, path: String): List<Quad> {
        request(ch, "LIST", path)
        // DENT is 20 bytes; FAIL/DONE are 8. Read 8 first, then the rest for DENT.
        val head = ByteArray(8)
        val rest = ByteArray(12)
        val out = ArrayList<Quad>()
        while (true) {
            readFully(ch.input, head)
            val tag = String(head, 0, 4, Charsets.US_ASCII)
            when (tag) {
                "DENT" -> {
                    readFully(ch.input, rest)
                    val mode = readLe32(head, 4)
                    val size = readLe32(rest, 0).toLong() and 0xffffffffL
                    val mtime = readLe32(rest, 4).toLong() and 0xffffffffL
                    val namelen = readLe32(rest, 8)
                    if (namelen == 0) return out
                    if (namelen < 0 || namelen > 1024) throw IOException("sync DENT namelen $namelen")
                    val nb = ByteArray(namelen)
                    readFully(ch.input, nb)
                    val name = String(nb, Charsets.UTF_8)
                    if (name != "." && name != "..") out += Quad(name, mode, size, mtime)
                }
                "FAIL" -> fail(ch.input, readLe32(head, 4))
                "DONE" -> return out // some older builds
                else -> throw IOException("unexpected sync reply $tag")
            }
        }
    }

    /** name, mode, size, mtimeSec — local to avoid a new public type in core. */
    data class Quad(val name: String, val mode: Int, val size: Long, val mtimeSec: Long)

    // Unix mode bits as hex (Kotlin has no 0o octal literals in this toolchain).
    const val S_IFMT = 0xF000
    const val S_IFIFO = 0x1000
    const val S_IFCHR = 0x2000
    const val S_IFDIR = 0x4000
    const val S_IFBLK = 0x6000
    const val S_IFREG = 0x8000
    const val S_IFLNK = 0xA000
    const val S_IFSOCK = 0xC000

    fun isDir(mode: Int) = (mode and S_IFMT) == S_IFDIR
    fun isLink(mode: Int) = (mode and S_IFMT) == S_IFLNK
    fun isReg(mode: Int) = (mode and S_IFMT) == S_IFREG

    /** ls-style mode string from a sync/stat mode word. */
    fun modeString(mode: Int): String {
        val t = when {
            isDir(mode) -> 'd'
            isLink(mode) -> 'l'
            isReg(mode) -> '-'
            (mode and S_IFMT) == S_IFIFO -> 'p'
            (mode and S_IFMT) == S_IFCHR -> 'c'
            (mode and S_IFMT) == S_IFBLK -> 'b'
            (mode and S_IFMT) == S_IFSOCK -> 's'
            else -> '?'
        }
        val chars = CharArray(10)
        chars[0] = t
        val bits = intArrayOf(0x100, 0x80, 0x40, 0x20, 0x10, 0x8, 0x4, 0x2, 0x1)
        val letters = charArrayOf('r', 'w', 'x', 'r', 'w', 'x', 'r', 'w', 'x')
        for (i in 0 until 9) chars[i + 1] = if ((mode and bits[i]) != 0) letters[i] else '-'
        // sticky / setuid / setgid
        if ((mode and 0x800) != 0) chars[3] = if (chars[3] == 'x') 's' else 'S'
        if ((mode and 0x400) != 0) chars[6] = if (chars[6] == 'x') 's' else 'S'
        if ((mode and 0x200) != 0) chars[9] = if (chars[9] == 'x') 't' else 'T'
        return String(chars)
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
