package com.ghostpanter.files.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutResolveTest {
    @Test fun phonePortraitStaysPhone() {
        val l = resolveLayout("auto", 411, 891)
        assertEquals(WidthClass.COMPACT, l.widthClass)
        assertFalse(l.tablet); assertFalse(l.permanentPanel); assertFalse(l.previewAllowed)
    }

    @Test fun landscapePhoneGetsRailButNoPanel() {
        val l = resolveLayout("auto", 891, 411)
        assertTrue(l.tablet); assertFalse(l.permanentPanel); assertFalse(l.previewAllowed)
    }

    @Test fun pixelTabletLandscape() {
        val l = resolveLayout("auto", 1280, 800)
        assertEquals(WidthClass.EXPANDED, l.widthClass)
        assertTrue(l.tablet); assertTrue(l.permanentPanel); assertTrue(l.previewAllowed)
    }

    @Test fun tabletPortraitAndFoldable() {
        val p = resolveLayout("auto", 800, 1280)
        assertTrue(p.tablet); assertFalse(p.permanentPanel)
        val fold = resolveLayout("auto", 673, 841)
        assertEquals(WidthClass.MEDIUM, fold.widthClass); assertTrue(fold.tablet)
    }

    @Test fun forcedModes() {
        assertFalse(resolveLayout("phone", 1280, 800).tablet)
        val forced = resolveLayout("tablet", 411, 891)
        assertTrue(forced.tablet); assertFalse(forced.permanentPanel); assertFalse(forced.previewAllowed)
    }

    @Test fun densityNormalMatchesPhoneDefaults() {
        val n = densitySpec("normal")
        assertEquals(28f, n.icon.value); assertEquals(96f, n.gridCell.value); assertEquals(56f, n.gridIcon.value)
        assertTrue(densitySpec("large").icon > n.icon)
    }
}
