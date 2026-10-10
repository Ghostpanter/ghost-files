package com.ghostpanter.files.core

import com.ghostpanter.files.core.ops.Archives
import com.ghostpanter.files.core.ops.FsSearch
import com.ghostpanter.files.core.ops.RecycleBin
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchivesTest {
    private val fs = LocalFs()

    @Test fun zipRoundTrip() {
        val src = tempDir(); val out = tempDir()
        val a = File(src, "pack/a.bin").writeRandom(70_000)
        val b = File(src, "pack/目录/b.txt").writeRandom(10)
        val zip = File(out, "p.zip").path
        val n = Archives.compressZip(fs, listOf(fs.stat(File(src, "pack").path)!!), fs, zip)
        assertEquals(2, n)
        Archives.extract(fs, zip, fs, File(out, "x").path)
        assertArrayEquals(a, File(out, "x/pack/a.bin").readBytes())
        assertArrayEquals(b, File(out, "x/pack/目录/b.txt").readBytes())
    }

    @Test fun gbkNamesAreDecoded() {
        val out = tempDir()
        val zip = File(out, "gbk.zip")
        ZipArchiveOutputStream(zip).use { z ->
            z.setEncoding("GBK"); z.setUseLanguageEncodingFlag(false)
            z.putArchiveEntry(ZipArchiveEntry("中文.txt")); z.write("hi".toByteArray()); z.closeArchiveEntry()
        }
        Archives.extract(fs, zip.path, fs, File(out, "x").path)
        assertEquals("hi", File(out, "x/中文.txt").readText())
    }

    @Test fun zipSlipRejected() {
        val out = tempDir()
        val zip = File(out, "evil.zip")
        ZipArchiveOutputStream(zip).use { z ->
            z.putArchiveEntry(ZipArchiveEntry("../../evil.txt")); z.write(1); z.closeArchiveEntry()
        }
        assertTrue(runCatching { Archives.extract(fs, zip.path, fs, File(out, "x").path) }.isFailure)
        assertFalse(File(out.parentFile, "evil.txt").exists())
    }

    @Test fun tarGz() {
        val out = tempDir()
        val tgz = File(out, "a.tar.gz")
        TarArchiveOutputStream(GzipCompressorOutputStream(tgz.outputStream())).use { t ->
            val data = "tar data".toByteArray()
            t.putArchiveEntry(TarArchiveEntry("d/f.txt").apply { size = data.size.toLong() }); t.write(data); t.closeArchiveEntry()
        }
        Archives.extract(fs, tgz.path, fs, File(out, "x").path)
        assertEquals("tar data", File(out, "x/d/f.txt").readText())
    }

    @Test fun searchGlobAndSubstring() {
        val d = tempDir()
        File(d, "a/photo1.JPG").writeRandom(1); File(d, "a/b/photo2.jpg").writeRandom(1); File(d, "note.txt").writeRandom(1)
        val found = ArrayList<String>()
        FsSearch.search(fs, d.path, "*.jpg") { found.add(it.name) }
        assertEquals(setOf("photo1.JPG", "photo2.jpg"), found.toSet())
        found.clear()
        FsSearch.search(fs, d.path, "NOTE") { found.add(it.name) }
        assertEquals(listOf("note.txt"), found)
    }

    @Test fun recycleBinRestore() {
        val d = tempDir()
        File(d, "x/keep.txt").writeRandom(5)
        val bin = RecycleBin(fs, File(d, ".trash").path)
        bin.moveToTrash(File(d, "x/keep.txt").path)
        assertFalse(File(d, "x/keep.txt").exists())
        val items = bin.list()
        assertEquals(1, items.size)
        bin.restore(items[0])
        assertTrue(File(d, "x/keep.txt").exists())
        assertTrue(bin.list().isEmpty())
    }
}
