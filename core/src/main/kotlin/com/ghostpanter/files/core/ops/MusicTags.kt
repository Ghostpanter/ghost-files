package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

import com.ghostpanter.files.core.FsException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

/**
 * Minimal ID3v1 + ID3v2.3 text-frame reader/writer for MP3.
 * FLAC/M4A tag editing is deferred.
 */
object MusicTags {
    data class Tags(
        val title: String = "",
        val artist: String = "",
        val album: String = "",
        val year: String = "",
        val comment: String = "",
    )

    fun read(file: File): Tags {
        if (!file.isFile) throw FsException(tr("文件不存在", "File not found"))
        val v2 = readId3v2(file)
        if (v2 != null && (v2.title.isNotEmpty() || v2.artist.isNotEmpty())) return v2
        return readId3v1(file) ?: Tags()
    }

    fun write(file: File, tags: Tags) {
        if (!file.isFile) throw FsException(tr("文件不存在", "File not found"))
        // Strip existing ID3v1, append new ID3v1 (safe, lossless for audio payload)
        val data = file.readBytes()
        val body = if (data.size >= 128 && data.copyOfRange(data.size - 128, data.size - 125).contentEquals("TAG".toByteArray())) {
            data.copyOfRange(0, data.size - 128)
        } else data
        // Also strip ID3v2 at start for clean rewrite of v1 only (keep v2 if present to avoid damage)
        val out = body + buildId3v1(tags)
        file.writeBytes(out)
    }

    private fun buildId3v1(t: Tags): ByteArray {
        val buf = ByteArray(128)
        "TAG".toByteArray().copyInto(buf, 0)
        fun put(s: String, off: Int, len: Int) {
            val b = s.toByteArray(Charsets.ISO_8859_1)
            System.arraycopy(b, 0, buf, off, minOf(b.size, len))
        }
        put(t.title, 3, 30); put(t.artist, 33, 30); put(t.album, 63, 30); put(t.year, 93, 4); put(t.comment, 97, 30)
        buf[127] = 0 // genre
        return buf
    }

    private fun readId3v1(file: File): Tags? {
        if (file.length() < 128) return null
        val buf = ByteArray(128)
        file.inputStream().use { ins ->
            ins.skip(file.length() - 128)
            if (ins.read(buf) != 128) return null
        }
        if (!buf.copyOfRange(0, 3).contentEquals("TAG".toByteArray())) return null
        fun field(off: Int, len: Int) = buf.copyOfRange(off, off + len).toString(Charsets.ISO_8859_1).trim { it <= ' ' || it == '\u0000' }
        return Tags(field(3, 30), field(33, 30), field(63, 30), field(93, 4), field(97, 30))
    }

    private fun readId3v2(file: File): Tags? {
        file.inputStream().use { ins ->
            val hdr = ByteArray(10)
            if (ins.read(hdr) != 10) return null
            if (!hdr.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) return null
            val size = ((hdr[6].toInt() and 0x7f) shl 21) or ((hdr[7].toInt() and 0x7f) shl 14) or
                ((hdr[8].toInt() and 0x7f) shl 7) or (hdr[9].toInt() and 0x7f)
            if (size <= 0 || size > 2_000_000) return null
            val body = ByteArray(size)
            var o = 0
            while (o < size) {
                val n = ins.read(body, o, size - o)
                if (n < 0) break
                o += n
            }
            var title = ""; var artist = ""; var album = ""; var year = ""; var comment = ""
            var i = 0
            while (i + 10 <= body.size) {
                val id = body.copyOfRange(i, i + 4).toString(Charsets.US_ASCII)
                if (id[0] < '0' || id[0] > 'Z') break
                val frameSize = ByteBuffer.wrap(body, i + 4, 4).order(ByteOrder.BIG_ENDIAN).int
                if (frameSize <= 0 || i + 10 + frameSize > body.size) break
                val data = body.copyOfRange(i + 10, i + 10 + frameSize)
                val text = decodeTextFrame(data)
                when (id) {
                    "TIT2" -> title = text
                    "TPE1" -> artist = text
                    "TALB" -> album = text
                    "TYER", "TDRC" -> year = text.take(4)
                    "COMM" -> comment = text
                }
                i += 10 + frameSize
            }
            return Tags(title, artist, album, year, comment)
        }
    }

    private fun decodeTextFrame(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val enc = data[0].toInt() and 0xff
        val payload = data.copyOfRange(1, data.size)
        val cs: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1, 2 -> Charsets.UTF_16
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        return payload.toString(cs).trim { it <= ' ' || it == '\u0000' }
    }
}
