package com.ghostpanter.files.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class NioListingTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun emptyFolderIsAnEmptyList() {
        val d = tmp.newFolder("adImage")
        assertEquals(emptyList<FileEntry>(), NioListing.parseList(d.path, NioListing.list(d.path)))
    }

    @Test fun sameEntriesAsShellFs() {
        val d = tmp.newFolder("files")
        File(d, "a b.jpg").writeText("12345")
        File(d, "sub").mkdir()
        Files.createSymbolicLink(File(d, "link-to-sub").toPath(), File(d, "sub").toPath())
        Files.createSymbolicLink(File(d, "dangling").toPath(), File(d, "missing").toPath())
        val nio = NioListing.parseList(d.path, NioListing.list(d.path)).associateBy { it.name }
        val sh = ShellFs("t", "t", ShellRunner { s -> ProcessBuilder("sh", "-c", s).start() }, persistent = false).list(d.path).associateBy { it.name }
        assertEquals(sh.keys, nio.keys)
        for (k in sh.keys) {
            val a = sh[k]!!; val b = nio[k]!!
            assertEquals(k, a.isDirectory, b.isDirectory); assertEquals(k, a.isLink, b.isLink)
            assertEquals(k, a.size, b.size); assertEquals(k, a.lastModified, b.lastModified); assertEquals(k, a.permissions, b.permissions)
            assertEquals(k, a.path, b.path)
        }
        assertTrue(nio["link-to-sub"]!!.isDirectory)
        assertFalse(nio["dangling"]!!.isDirectory)
    }

    @Test fun statAndErrors() {
        val f = tmp.newFile("x.txt").also { it.writeText("hi") }
        val e = NioListing.parseStat(f.path, NioListing.stat(f.path))!!
        assertEquals("x.txt", e.name); assertEquals(2, e.size); assertFalse(e.isDirectory)
        assertTrue(NioListing.parseStat(tmp.root.path, NioListing.stat(tmp.root.path))!!.isDirectory)
        assertNull(NioListing.parseStat("/nope/x", NioListing.stat("/nope/x")))
        try { NioListing.parseList("/nope", NioListing.list("/nope")); throw AssertionError() } catch (e: FsException) { assertTrue(e.message!!.contains("/nope")) }
    }

    @Test fun deleteDoesNotFollowLinks() {
        val keep = tmp.newFolder("keep").also { File(it, "precious").writeText("x") }
        val d = tmp.newFolder("del").also { File(it, "f").writeText("y"); File(it, "sub").mkdir() }
        Files.createSymbolicLink(File(d, "k").toPath(), keep.toPath())
        assertTrue(NioListing.delete(d, recursive = false) != null) // not empty
        assertNull(NioListing.delete(d, recursive = true))
        assertFalse(d.exists()); assertTrue(File(keep, "precious").exists())
        assertNull(NioListing.delete(File(tmp.root, "missing"), true))
    }
}
