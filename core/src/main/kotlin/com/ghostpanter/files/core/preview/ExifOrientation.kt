package com.ghostpanter.files.core.preview

/**
 * EXIF Orientation tag values (1–8) → clockwise rotation degrees and whether a flip is needed.
 * Pure logic so JVM unit tests can cover rotation without Android ExifInterface.
 */
data class ExifTransform(
    /** Clockwise rotation in degrees: 0, 90, 180, 270. */
    val rotationDegrees: Int,
    /** Horizontal flip (mirror) applied before rotation when true (tags 2, 4, 5, 7). */
    val flipHorizontally: Boolean,
)

object ExifOrientation {
    const val ORIENTATION_UNDEFINED = 0
    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_FLIP_HORIZONTAL = 2
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_FLIP_VERTICAL = 4
    const val ORIENTATION_TRANSPOSE = 5
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_TRANSVERSE = 7
    const val ORIENTATION_ROTATE_270 = 8

    fun transform(orientation: Int): ExifTransform = when (orientation) {
        ORIENTATION_FLIP_HORIZONTAL -> ExifTransform(0, true)
        ORIENTATION_ROTATE_180 -> ExifTransform(180, false)
        ORIENTATION_FLIP_VERTICAL -> ExifTransform(180, true) // flip H then rotate 180 ≡ flip V
        ORIENTATION_TRANSPOSE -> ExifTransform(90, true)
        ORIENTATION_ROTATE_90 -> ExifTransform(90, false)
        ORIENTATION_TRANSVERSE -> ExifTransform(270, true)
        ORIENTATION_ROTATE_270 -> ExifTransform(270, false)
        else -> ExifTransform(0, false)
    }

    /** True when the decoder / viewer should apply a non-identity transform. */
    fun needsCorrection(orientation: Int): Boolean =
        orientation in 2..8

    /**
     * Swap display width/height when rotation is 90 or 270.
     */
    fun swapDimensions(orientation: Int): Boolean {
        val deg = transform(orientation).rotationDegrees
        return deg == 90 || deg == 270
    }
}

/**
 * Camera / capture metadata shown in the image viewer info panel.
 * Fields are nullable strings already formatted for display.
 */
data class ExifInfo(
    val model: String? = null,
    val make: String? = null,
    val aperture: String? = null,
    val shutter: String? = null,
    val iso: String? = null,
    val focalLength: String? = null,
    val dateTime: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val orientation: Int = ExifOrientation.ORIENTATION_NORMAL,
) {
    val hasAny: Boolean
        get() = listOf(model, make, aperture, shutter, iso, focalLength, dateTime).any { !it.isNullOrBlank() } ||
            width != null || height != null || latitude != null || longitude != null

    fun sizeLabel(): String? = when {
        width != null && height != null -> {
            val (w, h) = if (ExifOrientation.swapDimensions(orientation)) height!! to width!! else width to height
            "${w}×${h}"
        }
        else -> null
    }

    fun gpsLabel(): String? = when {
        latitude != null && longitude != null ->
            String.format(java.util.Locale.US, "%.5f, %.5f", latitude, longitude)
        else -> null
    }
}
