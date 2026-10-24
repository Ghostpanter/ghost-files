package com.ghostpanter.files.core.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSearchTest {
    @Test fun findsAcrossPagesCaseInsensitive() {
        val pages = listOf("Hello World", "Nothing here", "hello again HELLO")
        val hits = PdfSearch.findInPages(pages, "hello")
        assertEquals(3, hits.size)
        assertEquals(0, hits[0].page)
        assertEquals(0, hits[0].charIndex)
        assertEquals(2, hits[1].page)
        assertEquals(2, hits[2].page)
        assertTrue(hits[0].preview.contains("Hello", ignoreCase = true))
    }

    @Test fun respectsMaxHitsAndBlank() {
        val pages = listOf("aaa aaa aaa")
        assertEquals(2, PdfSearch.findInPages(pages, "aaa", maxHits = 2).size)
        assertTrue(PdfSearch.findInPages(pages, "   ").isEmpty())
        assertTrue(PdfSearch.findInPages(emptyList(), "x").isEmpty())
    }

    @Test fun previewEllipsis() {
        val long = "x".repeat(80) + "NEEDLE" + "y".repeat(80)
        val hit = PdfSearch.findInPages(listOf(long), "NEEDLE").single()
        assertTrue(hit.preview.startsWith("…"))
        assertTrue(hit.preview.endsWith("…"))
        assertTrue(hit.preview.contains("NEEDLE"))
        assertEquals(80, hit.charIndex)
        assertEquals(6, hit.charLength)
    }
}
