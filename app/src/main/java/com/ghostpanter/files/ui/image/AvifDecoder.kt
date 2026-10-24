package com.ghostpanter.files.ui.image

import android.graphics.Bitmap
import android.os.Build
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import okio.BufferedSource
import org.aomedia.avif.android.AvifDecoder as LibAvif
import java.nio.ByteBuffer

/**
 * Coil 3 decoder backed by AOM libavif for API &lt; 31 (Android 12+ uses the system decoder).
 * Decodes the first frame (still AVIF and a readable first frame for animated AVIF).
 */
class AvifDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {
    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val buf = ByteBuffer.allocateDirect(bytes.size).apply {
            put(bytes)
            flip()
        }
        val info = LibAvif.Info()
        check(LibAvif.getInfo(buf, buf.remaining(), info)) { "Not a valid AVIF" }
        buf.rewind()
        val bmp = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        check(LibAvif.decode(buf, buf.remaining(), bmp)) { "AVIF decode failed" }
        return DecodeResult(image = bmp.asImage(), isSampled = false)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            if (Build.VERSION.SDK_INT >= 31) return null
            if (!isAvif(result.source.source(), result.mimeType)) return null
            return AvifDecoder(result.source, options)
        }

        private fun isAvif(source: BufferedSource, mime: String?): Boolean {
            if (mime?.contains("avif", ignoreCase = true) == true) return true
            if (!source.request(12)) return false
            val hdr = source.peek().readByteArray(12)
            if (hdr.size < 12) return false
            if (hdr[4] != 'f'.code.toByte() || hdr[5] != 't'.code.toByte() ||
                hdr[6] != 'y'.code.toByte() || hdr[7] != 'p'.code.toByte()
            ) return false
            val brand = String(hdr, 8, 4, Charsets.US_ASCII)
            return brand == "avif" || brand == "avis" || brand == "avio" || brand == "mif1"
        }
    }
}
