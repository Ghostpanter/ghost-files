package com.ghostpanter.files.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExifOrientationTest {
    @Test fun normalAndUndefined() {
        assertEquals(ExifTransform(0, false), ExifOrientation.transform(1))
        assertEquals(ExifTransform(0, false), ExifOrientation.transform(0))
        assertFalse(ExifOrientation.needsCorrection(1))
    }

    @Test fun rotations() {
        assertEquals(ExifTransform(90, false), ExifOrientation.transform(6))
        assertEquals(ExifTransform(180, false), ExifOrientation.transform(3))
        assertEquals(ExifTransform(270, false), ExifOrientation.transform(8))
        assertTrue(ExifOrientation.swapDimensions(6))
        assertTrue(ExifOrientation.swapDimensions(8))
        assertFalse(ExifOrientation.swapDimensions(3))
    }

    @Test fun flips() {
        assertEquals(ExifTransform(0, true), ExifOrientation.transform(2))
        assertEquals(ExifTransform(90, true), ExifOrientation.transform(5))
        assertEquals(ExifTransform(270, true), ExifOrientation.transform(7))
        assertTrue(ExifOrientation.needsCorrection(2))
    }

    @Test fun infoLabels() {
        val info = ExifInfo(
            model = "Pixel",
            width = 4000,
            height = 3000,
            orientation = 6,
            latitude = 31.2304,
            longitude = 121.4737,
        )
        assertEquals("3000×4000", info.sizeLabel())
        assertTrue(info.gpsLabel()!!.contains("31.23040"))
        assertTrue(info.hasAny)
        assertFalse(ExifInfo().hasAny)
    }
}
