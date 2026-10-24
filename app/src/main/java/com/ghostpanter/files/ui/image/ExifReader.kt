package com.ghostpanter.files.ui.image

import androidx.exifinterface.media.ExifInterface
import com.ghostpanter.files.core.preview.ExifInfo
import java.io.File
import java.io.InputStream

/** Read capture metadata via androidx.exifinterface (also used to drive orientation correction). */
object ExifReader {
    fun read(file: File): ExifInfo = runCatching {
        file.inputStream().use { read(it) }
    }.getOrElse { ExifInfo() }

    fun read(stream: InputStream): ExifInfo {
        val exif = ExifInterface(stream)
        val latLong = FloatArray(2)
        val hasGps = exif.getLatLong(latLong)
        fun tag(name: String): String? = exif.getAttribute(name)?.takeIf { it.isNotBlank() }
        val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        return ExifInfo(
            model = tag(ExifInterface.TAG_MODEL),
            make = tag(ExifInterface.TAG_MAKE),
            aperture = tag(ExifInterface.TAG_F_NUMBER)?.let { "f/$it" }
                ?: tag(ExifInterface.TAG_APERTURE_VALUE)?.let { "f/$it" },
            shutter = formatShutter(tag(ExifInterface.TAG_EXPOSURE_TIME)),
            iso = tag(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
                ?: tag(ExifInterface.TAG_ISO_SPEED_RATINGS),
            focalLength = tag(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${it}mm" },
            dateTime = tag(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: tag(ExifInterface.TAG_DATETIME),
            width = exif.getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_X_DIMENSION, 0).takeIf { it > 0 },
            height = exif.getAttributeInt(ExifInterface.TAG_IMAGE_LENGTH, 0).takeIf { it > 0 }
                ?: exif.getAttributeInt(ExifInterface.TAG_PIXEL_Y_DIMENSION, 0).takeIf { it > 0 },
            latitude = if (hasGps) latLong[0].toDouble() else null,
            longitude = if (hasGps) latLong[1].toDouble() else null,
            orientation = orient,
        )
    }

    private fun formatShutter(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val v = raw.toDoubleOrNull() ?: return raw + "s"
        return when {
            v >= 1.0 -> String.format(java.util.Locale.US, "%.1fs", v)
            v > 0 -> {
                val den = (1.0 / v).toInt().coerceAtLeast(1)
                "1/${den}s"
            }
            else -> raw
        }
    }
}
