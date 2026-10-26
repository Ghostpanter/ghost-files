package com.ghostpanter.files.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwipeSelectTest {
    private val list = listOf("/a", "/b", "/c", "/d", "/e", "/f")

    @Test fun firstSwipeSelectsAndAnchors() {
        val r = swipeSelect(emptySet(), null, list, "/b")
        assertEquals(setOf("/b"), r.selected)
        assertEquals("/b", r.anchor)
    }

    @Test fun secondSwipeSelectsInclusiveRangeDownward() {
        val r = swipeSelect(setOf("/b"), "/b", list, "/e")
        assertEquals(setOf("/b", "/c", "/d", "/e"), r.selected)
        assertEquals("/e", r.anchor)
    }

    @Test fun secondSwipeSelectsRangeUpward() {
        val r = swipeSelect(setOf("/e"), "/e", list, "/b")
        assertEquals(setOf("/b", "/c", "/d", "/e"), r.selected)
    }

    @Test fun tappedItemsAreKeptAndRangeOverlapStaysSelected() {
        // swipe /a, tap /c and /f (tap keeps anchor), then swipe /d → a..d plus f
        val r = swipeSelect(setOf("/a", "/c", "/f"), "/a", list, "/d")
        assertEquals(setOf("/a", "/b", "/c", "/d", "/f"), r.selected)
    }

    @Test fun rangeChainsFromNewAnchor() {
        val r1 = swipeSelect(setOf("/a"), "/a", list, "/b")
        val r2 = swipeSelect(r1.selected, r1.anchor, list, "/d")
        assertEquals(setOf("/a", "/b", "/c", "/d"), r2.selected)
        assertEquals("/d", r2.anchor)
    }

    @Test fun swipingAnchorAgainDeselectsIt() {
        val r = swipeSelect(setOf("/b"), "/b", list, "/b")
        assertEquals(emptySet<String>(), r.selected)
        assertNull(r.anchor)
    }

    @Test fun deselectedAnchorFallsBackToSingleSelect() {
        // anchor /a was untapped → next swipe is a plain select, not a range
        val r = swipeSelect(setOf("/c"), "/a", list, "/e")
        assertEquals(setOf("/c", "/e"), r.selected)
        assertEquals("/e", r.anchor)
    }

    @Test fun anchorFilteredOutFallsBackToSingleSelect() {
        val r = swipeSelect(setOf("/zz"), "/zz", list, "/c")
        assertEquals(setOf("/zz", "/c"), r.selected)
    }

    @Test fun swipingSelectedItemWithoutAnchorDeselects() {
        val r = swipeSelect(setOf("/c", "/d"), null, list, "/c")
        assertEquals(setOf("/d"), r.selected)
    }
}
