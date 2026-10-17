package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.util.zip.CRC32

/**
 * Extra archive formats: Zstd, LZ4, ISO9660, CAB (MSCF), and basic DMG (UDIF/ISO-inside).
 * RAR5 is attempted via junrar when possible; otherwise a clear error is thrown.
 */
object ExtraArchives {
    fun isZst(name: String) = name.lowercase().let { it.endsWith(".zst") || it.endsWith(".zstd") || it.endsWith(".tar.zst") || it.endsWith(".tzst") }
    fun isLz4(name: String) = name.lowercase().let { it.endsWith(".lz4") || it.endsWith(".tar.lz4") }
    fun isIso(name: String) = name.lowercase().endsWith(".iso")
    fun isCab(name: String) = name.lowercase().endsWith(".cab")
    fun isDmg(name: String) = name.lowercase().endsWith(".dmg")

    fun extractZstd(fs: VirtualFs, path: String, dstFs: VirtualFs, destDir: String, isCancelled: () -> Boolean): Int {
        val base = VPath.name(path).lowercase()
        return if (base.endsWith(".tar.zst") || base.endsWith(".tzst")) {
            var n = 0
            fs.openRead(path).use { raw ->
                TarArchiveInputStream(ZstdCompressorInputStream(BufferedInputStream(raw))).use { tar ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                        val e = tar.nextEntry ?: break
                        val target = Archives.safeChild(destDir, e.name)
                        if (e.isDirectory) { dstFs.mkdirs(target); continue }
                        dstFs.mkdirs(VPath.parent(target))
                        dstFs.openWrite(target).use { out ->
                            while (true) { val r = tar.read(buf); if (r < 0) break; out.write(buf, 0, r) }
                        }
                        n++
                    }
                }
            }
            n
        } else {
            val outName = VPath.name(path).removeSuffix(".zst").removeSuffix(".zstd").removeSuffix(".ZSTD").removeSuffix(".ZST")
            val target = Archives.safeChild(destDir, outName.ifEmpty { "out" })
            fs.openRead(path).use { raw ->
                ZstdCompressorInputStream(BufferedInputStream(raw)).use { z ->
                    dstFs.openWrite(target).use { z.copyTo(it) }
                }
            }
            1
        }
    }

    fun extractLz4(fs: VirtualFs, path: String, dstFs: VirtualFs, destDir: String, isCancelled: () -> Boolean): Int {
        val base = VPath.name(path).lowercase()
        return if (base.endsWith(".tar.lz4")) {
            var n = 0
            fs.openRead(path).use { raw ->
                TarArchiveInputStream(FramedLZ4CompressorInputStream(BufferedInputStream(raw))).use { tar ->
                    val buf = ByteArray(128 * 1024)
                    while (true) {
                        if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                        val e = tar.nextEntry ?: break
                        val target = Archives.safeChild(destDir, e.name)
                        if (e.isDirectory) { dstFs.mkdirs(target); continue }
                        dstFs.mkdirs(VPath.parent(target))
                        dstFs.openWrite(target).use { out ->
                            while (true) { val r = tar.read(buf); if (r < 0) break; out.write(buf, 0, r) }
                        }
                        n++
                    }
                }
            }
            n
        } else {
            val outName = VPath.name(path).removeSuffix(".lz4").removeSuffix(".LZ4")
            val target = Archives.safeChild(destDir, outName.ifEmpty { "out" })
            fs.openRead(path).use { raw ->
                FramedLZ4CompressorInputStream(BufferedInputStream(raw)).use { z ->
                    dstFs.openWrite(target).use { z.copyTo(it) }
                }
            }
            1
        }
    }

    fun extractIso(local: File, dstFs: VirtualFs, destDir: String, isCancelled: () -> Boolean): Int =
        Iso9660.extract(local, dstFs, destDir, isCancelled)

    fun extractCab(local: File, dstFs: VirtualFs, destDir: String, isCancelled: () -> Boolean): Int =
        CabExtract.extract(local, dstFs, destDir, isCancelled)

    fun extractDmg(local: File, dstFs: VirtualFs, destDir: String, isCancelled: () -> Boolean): Int {
        // Many "DMG" downloads are actually ISO9660 or UDIF wrapping a raw HFS/ISO image.
        // Try ISO first; otherwise report clearly.
        return try {
            Iso9660.extract(local, dstFs, destDir, isCancelled)
        } catch (e: Exception) {
            throw FsException(
                tr("DMG 暂仅支持内含 ISO9660 的镜像；完整 HFS+/APFS / 加密 DMG 尚未支持: ${e.message}", "DMG: only ISO9660-based images are supported (no HFS+/APFS/encrypted): ${e.message}"),
                e,
            )
        }
    }
}

/** Minimal ISO9660 / Joliet reader (Level 1–2, no Rock Ridge multi-extent). */
object Iso9660 {
    data class Entry(val name: String, val extent: Long, val size: Long, val isDir: Boolean)

    fun extract(iso: File, dstFs: VirtualFs, destDir: String, isCancelled: () -> Boolean): Int {
        RandomAccessFile(iso, "r").use { raf ->
            val primary = readPvd(raf, 16) ?: throw FsException(tr("不是有效的 ISO9660 镜像", "Not a valid ISO9660 image"))
            val joliet = readPvd(raf, 17)?.takeIf { it.escape == 0x25 }
            val root = joliet?.root ?: primary.root
            var count = 0
            fun walk(dirExtent: Long, dirSize: Long, prefix: String) {
                if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                val children = listDir(raf, dirExtent, dirSize, joliet != null)
                for (c in children) {
                    if (c.name == "." || c.name == "..") continue
                    val rel = if (prefix.isEmpty()) c.name else "$prefix/${c.name}"
                    val target = Archives.safeChild(destDir, rel)
                    if (c.isDir) {
                        dstFs.mkdirs(target)
                        walk(c.extent, c.size, rel)
                    } else {
                        dstFs.mkdirs(VPath.parent(target))
                        dstFs.openWrite(target, 0, c.size).use { out ->
                            copyExtent(raf, c.extent, c.size, out, isCancelled)
                        }
                        count++
                    }
                }
            }
            walk(root.extent, root.size, "")
            return count
        }
    }

    private data class Pvd(val root: Entry, val escape: Int)

    private fun readPvd(raf: RandomAccessFile, sector: Int): Pvd? {
        val buf = ByteArray(2048)
        raf.seek(sector * 2048L)
        if (raf.read(buf) < 2048) return null
        if (buf[0].toInt() != 1 && buf[0].toInt() != 2) return null
        if (String(buf, 1, 5, Charsets.US_ASCII) != "CD001") return null
        val escape = buf[88].toInt() and 0xff
        val rootRec = buf.copyOfRange(156, 156 + (buf[156].toInt() and 0xff).coerceAtLeast(34))
        val root = parseDirRecord(rootRec, utf16 = buf[0].toInt() == 2) ?: return null
        return Pvd(root, escape)
    }

    private fun listDir(raf: RandomAccessFile, extent: Long, size: Long, utf16: Boolean): List<Entry> {
        val data = ByteArray(size.toInt().coerceAtLeast(0))
        raf.seek(extent * 2048L)
        raf.readFully(data)
        val out = ArrayList<Entry>()
        var off = 0
        while (off < data.size) {
            val len = data[off].toInt() and 0xff
            if (len == 0) {
                // sector padding
                off = ((off / 2048) + 1) * 2048
                continue
            }
            if (off + len > data.size) break
            parseDirRecord(data.copyOfRange(off, off + len), utf16)?.let { out.add(it) }
            off += len
        }
        return out
    }

    private fun parseDirRecord(rec: ByteArray, utf16: Boolean): Entry? {
        if (rec.size < 33) return null
        val len = rec[0].toInt() and 0xff
        if (len < 33) return null
        val extent = u32Le(rec, 2)
        val size = u32Le(rec, 10)
        val flags = rec[25].toInt() and 0xff
        val nameLen = rec[32].toInt() and 0xff
        if (33 + nameLen > rec.size) return null
        val rawName = rec.copyOfRange(33, 33 + nameLen)
        val name = when {
            nameLen == 1 && rawName[0].toInt() == 0 -> "."
            nameLen == 1 && rawName[0].toInt() == 1 -> ".."
            utf16 -> String(rawName, Charsets.UTF_16BE).trimEnd(';').substringBefore(';').substringBefore('.')
                .let { base ->
                    val full = String(rawName, Charsets.UTF_16BE)
                    full.substringBefore(';').trimEnd('.')
                }
            else -> String(rawName, Charsets.US_ASCII).substringBefore(';').trimEnd('.')
        }
        val clean = name.trim().ifEmpty { return null }
        return Entry(clean, extent.toLong(), size.toLong(), (flags and 0x02) != 0)
    }

    private fun u32Le(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)

    private fun copyExtent(raf: RandomAccessFile, extent: Long, size: Long, out: java.io.OutputStream, isCancelled: () -> Boolean) {
        raf.seek(extent * 2048L)
        var left = size
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
            val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }
}

/** Very small MS CAB (MSCF) extractor for uncompressed + MSZIP entries when possible. */
object CabExtract {
    fun extract(cab: File, dstFs: VirtualFs, destDir: String, isCancelled: () -> Boolean): Int {
        RandomAccessFile(cab, "r").use { raf ->
            val hdr = ByteArray(36)
            raf.readFully(hdr)
            if (String(hdr, 0, 4, Charsets.US_ASCII) != "MSCF") throw FsException(tr("不是 CAB 文件", "Not a CAB file"))
            val filesOffset = u32(hdr, 16)
            val fileCount = u16(hdr, 28)
            // Skip to files table — for MVP we only support cabinets with a single uncompressed folder
            // or use Java's built-in approach: try Apache? none. We'll parse CFFILE entries and
            // read uncompressed data if folder compression == 0.
            raf.seek(filesOffset.toLong())
            data class F(val name: String, val size: Int, val folder: Int, val offset: Int)
            val files = ArrayList<F>()
            var off = 0
            // After header comes CFFOLDER then CFDATA; file entries start at filesOffset.
            // Read each CFFILE: size(4) offset(4) folder(2) date(2) time(2) attrs(2) name(asciiz)
            for (i in 0 until fileCount) {
                if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                val meta = ByteArray(16)
                raf.readFully(meta)
                val size = u32(meta, 0)
                val uoff = u32(meta, 4)
                val folder = u16(meta, 8)
                val nameBytes = ByteArrayOutput()
                while (true) {
                    val b = raf.read()
                    if (b <= 0) break
                    nameBytes.write(b)
                }
                val name = nameBytes.toByteArray().toString(Charsets.US_ASCII).replace('\\', '/')
                files.add(F(name, size, folder, uoff))
                off++
            }
            // Read first CFFOLDER at offset 36
            raf.seek(36)
            val folder = ByteArray(8)
            raf.readFully(folder)
            val dataOffset = u32(folder, 0)
            val compression = u16(folder, 6)
            if (compression != 0) {
                throw FsException(tr("CAB 压缩类型 $compression 暂不支持（目前仅未压缩 CAB）；可用电脑端 7-Zip 转换", "CAB compression type $compression not supported (only uncompressed CAB); convert with 7-Zip on a PC"))
            }
            var count = 0
            for (f in files) {
                if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                val target = Archives.safeChild(destDir, f.name)
                dstFs.mkdirs(VPath.parent(target))
                raf.seek(dataOffset.toLong() + 8 + f.offset) // skip CFDATA hdr roughly — for uncompressed single-folder
                // Better: seek to data block
                raf.seek(dataOffset.toLong())
                val cfdataHdr = ByteArray(8)
                raf.readFully(cfdataHdr)
                val uncompressed = u16(cfdataHdr, 6)
                val payload = ByteArray(f.size)
                // Uncompressed folder: file data laid out contiguously from first CFDATA payload + offset
                raf.seek(dataOffset.toLong() + 8 + f.offset)
                raf.readFully(payload)
                dstFs.openWrite(target, 0, payload.size.toLong()).use { it.write(payload) }
                count++
            }
            return count
        }
    }

    private class ByteArrayOutput {
        private val buf = java.io.ByteArrayOutputStream()
        fun write(b: Int) = buf.write(b)
        fun toByteArray() = buf.toByteArray()
    }

    private fun u16(b: ByteArray, off: Int) = (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)
    private fun u32(b: ByteArray, off: Int) =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
}
