package com.ghostpanter.adb

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Reads single entries of a remote ZIP/APK through ranged reads (end record, central directory,
 * then only the wanted entries), so an app's manifest / resource table / icon can be fetched
 * without pulling the whole APK. Supports ZIP64 end records; STORED and DEFLATED entries.
 */
class ZipRange(private val size: Long, private val read: (offset: Long, len: Int) -> ByteArray) {

    data class Entry(val name: String, val method: Int, val crc: Long, val csize: Long, val usize: Long, val localOffset: Long)

    val entries: Map<String, Entry> by lazy { parseCentralDirectory() }

    private fun parseCentralDirectory(): Map<String, Entry> {
        val tailLen = minOf(size, 22L + 65535L).toInt()
        val tailStart = size - tailLen
        val tail = read(tailStart, tailLen)
        var i = tail.size - 22
        while (i >= 0 && u32(tail, i) != 0x06054b50L) i--
        if (i < 0) throw IOException("not a zip (no end record)")
        var cdSize = u32(tail, i + 12)
        var cdOff = u32(tail, i + 16)
        if (cdOff == 0xffffffffL || cdSize == 0xffffffffL) {
            val loc = i - 20
            if (loc < 0 || u32(tail, loc) != 0x07064b50L) throw IOException("zip64 locator missing")
            val eocd64 = u64(tail, loc + 8)
            val rec = if (eocd64 >= tailStart) tail.copyOfRange((eocd64 - tailStart).toInt(), (eocd64 - tailStart).toInt() + 56) else read(eocd64, 56)
            if (u32(rec, 0) != 0x06064b50L) throw IOException("bad zip64 end record")
            cdSize = u64(rec, 40); cdOff = u64(rec, 48)
        }
        if (cdSize > 64L shl 20) throw IOException("central directory too large")
        val cd = if (cdOff >= tailStart && cdOff + cdSize <= size) tail.copyOfRange((cdOff - tailStart).toInt(), (cdOff - tailStart + cdSize).toInt())
        else read(cdOff, cdSize.toInt())
        val out = LinkedHashMap<String, Entry>()
        var p = 0
        while (p + 46 <= cd.size && u32(cd, p) == 0x02014b50L) {
            val method = u16(cd, p + 10)
            val crc = u32(cd, p + 16)
            var csize = u32(cd, p + 20)
            var usize = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28)
            val extraLen = u16(cd, p + 30)
            val commentLen = u16(cd, p + 32)
            var local = u32(cd, p + 42)
            val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
            // zip64 extended info
            var x = p + 46 + nameLen
            val xEnd = x + extraLen
            while (x + 4 <= xEnd) {
                val id = u16(cd, x); val len = u16(cd, x + 2)
                if (id == 0x0001) {
                    var q = x + 4
                    if (usize == 0xffffffffL) { usize = u64(cd, q); q += 8 }
                    if (csize == 0xffffffffL) { csize = u64(cd, q); q += 8 }
                    if (local == 0xffffffffL) { local = u64(cd, q) }
                }
                x += 4 + len
            }
            out[name] = Entry(name, method, crc, csize, usize, local)
            p += 46 + nameLen + extraLen + commentLen
        }
        return out
    }

    /** Uncompressed bytes of [name], or null when absent / larger than [maxBytes]. */
    fun bytes(name: String, maxBytes: Long = 32L shl 20): ByteArray? {
        val e = entries[name] ?: return null
        if (e.usize > maxBytes || e.csize > maxBytes) return null
        // One round trip: local header + name + a generous extra-field guess + data.
        val guess = 30 + name.toByteArray(Charsets.UTF_8).size + 64
        val first = read(e.localOffset, (guess + e.csize).toInt().coerceAtMost((size - e.localOffset).toInt()))
        if (first.size < 30 || u32(first, 0) != 0x04034b50L) throw IOException("bad local header: $name")
        val hdr = 30 + u16(first, 26) + u16(first, 28)
        val raw = when {
            e.csize == 0L -> ByteArray(0)
            hdr + e.csize <= first.size -> first.copyOfRange(hdr, hdr + e.csize.toInt())
            else -> read(e.localOffset + hdr, e.csize.toInt())
        }
        if (raw.size.toLong() != e.csize) throw IOException("short read: $name")
        return when (e.method) {
            0 -> raw
            8 -> {
                val inf = Inflater(true)
                try {
                    inf.setInput(raw)
                    val bo = ByteArrayOutputStream(e.usize.toInt().coerceAtLeast(64))
                    val b = ByteArray(64 * 1024)
                    while (!inf.finished()) {
                        val n = inf.inflate(b)
                        if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                        bo.write(b, 0, n)
                    }
                    bo.toByteArray()
                } finally { inf.end() }
            }
            else -> throw IOException("unsupported zip method ${e.method}: $name")
        }
    }

    companion object {
        fun u16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
        fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong()) or (u16(b, i + 2).toLong() shl 16)
        fun u64(b: ByteArray, i: Int): Long = u32(b, i) or (u32(b, i + 4) shl 32)

        /**
         * Writes a minimal STORED zip with every entry 4-byte aligned (the platform rejects a
         * compressed / unaligned resources.arsc when parsing an R+ package).
         */
        fun writeStoredAligned(entries: Map<String, ByteArray>, out: OutputStream) {
            class Cd(val name: ByteArray, val crc: Long, val size: Int, val off: Long)
            val cds = ArrayList<Cd>()
            var pos = 0L
            fun w16(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xff); o.write((v ushr 8) and 0xff) }
            fun w32(o: ByteArrayOutputStream, v: Long) { w16(o, (v and 0xffff).toInt()); w16(o, ((v ushr 16) and 0xffff).toInt()) }
            for ((n, data) in entries) {
                val name = n.toByteArray(Charsets.UTF_8)
                val crc = CRC32().apply { update(data) }.value
                val base = pos + 30 + name.size
                var pad = ((4 - ((base + 6) % 4)) % 4).toInt()
                val h = ByteArrayOutputStream()
                w32(h, 0x04034b50L); w16(h, 10); w16(h, 0); w16(h, 0); w16(h, 0); w16(h, 0x21)
                w32(h, crc); w32(h, data.size.toLong()); w32(h, data.size.toLong())
                w16(h, name.size); w16(h, 6 + pad)
                h.write(name)
                w16(h, 0xD935); w16(h, 2 + pad); w16(h, 4); repeat(pad) { h.write(0) }
                val hb = h.toByteArray()
                out.write(hb); out.write(data)
                cds += Cd(name, crc, data.size, pos)
                pos += hb.size + data.size
            }
            val cdStart = pos
            val c = ByteArrayOutputStream()
            for (e in cds) {
                w32(c, 0x02014b50L); w16(c, 10); w16(c, 10); w16(c, 0); w16(c, 0); w16(c, 0); w16(c, 0x21)
                w32(c, e.crc); w32(c, e.size.toLong()); w32(c, e.size.toLong())
                w16(c, e.name.size); w16(c, 0); w16(c, 0); w16(c, 0); w16(c, 0); w32(c, 0); w32(c, e.off)
                c.write(e.name)
            }
            val cdb = c.toByteArray()
            out.write(cdb)
            val end = ByteArrayOutputStream()
            w32(end, 0x06054b50L); w16(end, 0); w16(end, 0); w16(end, cds.size); w16(end, cds.size)
            w32(end, cdb.size.toLong()); w32(end, cdStart); w16(end, 0)
            out.write(end.toByteArray())
        }
    }
}
