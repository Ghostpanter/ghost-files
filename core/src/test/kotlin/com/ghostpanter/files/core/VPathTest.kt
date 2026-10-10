package com.ghostpanter.files.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VPathTest {
    @Test fun normalizeAndJoin() {
        assertEquals("/", VPath.normalize(""))
        assertEquals("/a/c", VPath.normalize("/a/b/../c/"))
        assertEquals("/a/b", VPath.join("/a", "b"))
        assertEquals("/b", VPath.join("/", "b"))
        assertEquals("/a", VPath.parent("/a/b"))
        assertEquals("/", VPath.parent("/a"))
        assertEquals("b.txt", VPath.name("/a/b.txt"))
    }

    @Test fun descendants() {
        assertTrue(VPath.isSameOrDescendant("/a", "/a/b"))
        assertTrue(VPath.isSameOrDescendant("/a", "/a"))
        assertFalse(VPath.isSameOrDescendant("/a", "/ab"))
        assertEquals("b/c", VPath.relativize("/a", "/a/b/c"))
    }

    @Test fun numberedAndQuote() {
        assertEquals("a (1).txt", VPath.numbered("a.txt", 1))
        assertEquals("a (2)", VPath.numbered("a", 2))
        assertEquals("'it'\\''s'", VPath.shellQuote("it's"))
    }
}
