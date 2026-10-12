package com.ghostpanter.files.core

import com.ghostpanter.files.core.ops.Archives
import com.ghostpanter.files.core.ops.BatchRename
import com.ghostpanter.files.core.ops.Checksums
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpsV040Test {
    private val fs = LocalFs()

    @Test fun checksumsKnownVectors() {
        val r = Checksums.compute("abc".byteInputStream(), Checksums.Algo.values().toSet())
        assertEquals("900150983cd24fb0d6963f7d28e17f72", r[Checksums.Algo.MD5])
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", r[Checksums.Algo.SHA1])
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", r[Checksums.Algo.SHA256])
        assertEquals("352441c2", r[Checksums.Algo.CRC32])
        assertEquals(Checksums.Algo.MD5, Checksums.match(r, " 900150983CD24FB0D6963F7D28E17F72  abc.txt"))
        assertNull(Checksums.match(r, "deadbeef"))
    }

    private fun e(name: String, dir: Boolean = false) = FileEntry("/d/$name", name, dir, 1, 0)

    @Test fun batchRenameTemplateAndCounter() {
        val s = BatchRename.Spec(template = "IMG_{n:3}.{ext}", start = 1)
        val plan = BatchRename.plan(s, listOf(e("a.JPG"), e("b.png"), e("noext")), emptySet())
        assertEquals(listOf("IMG_001.JPG", "IMG_002.png", "IMG_003"), plan.map { it.newName })
        assertTrue(plan.all { it.error == null })
    }

    @Test fun batchRenameDetectsClashesAndSwaps() {
        val clash = BatchRename.plan(BatchRename.Spec(template = "x.{ext}"), listOf(e("a.txt"), e("b.txt")), emptySet())
        assertTrue(clash.all { it.error != null })
        val exists = BatchRename.plan(BatchRename.Spec(template = "c.{ext}"), listOf(e("a.txt")), setOf("a.txt", "c.txt"))
        assertTrue(exists[0].error != null)
        // real swap on disk via temp names
        val d = tempDir()
        File(d, "a.txt").writeText("A"); File(d, "b.txt").writeText("B")
        val entries = listOf(fs.stat(File(d, "a.txt").path)!!, fs.stat(File(d, "b.txt").path)!!)
        val plans = listOf(BatchRename.Plan(entries[0], "b.txt"), BatchRename.Plan(entries[1], "a.txt"))
        assertTrue(BatchRename.execute(plans) { f, t -> fs.rename(f, t) }.isEmpty())
        assertEquals("B", File(d, "a.txt").readText()); assertEquals("A", File(d, "b.txt").readText())
    }

    @Test fun batchRenameRegexAndCase() {
        val s = BatchRename.Spec(template = "{name}.{ext}", find = "v(\\d+)", replace = "v#$1", regex = true, case = BatchRename.Case.UPPER)
        assertEquals("V#12.MP4", BatchRename.apply(s, e("v12.mp4"), 0))
    }

    @Test fun createTarGz7zRoundTrip() {
        for (kind in listOf(Archives.Kind.TAR_GZ, Archives.Kind.TAR_XZ, Archives.Kind.TAR, Archives.Kind.SEVEN_Z, Archives.Kind.ZIP)) {
            val src = tempDir(); val out = tempDir()
            val a = File(src, "pack/a.bin").writeRandom(150_000)
            val b = File(src, "pack/中文目录/b.txt").writeRandom(7)
            File(src, "pack/empty").mkdirs()
            val dst = File(out, "p" + Archives.extensionOf(kind)).path
            val n = Archives.compress(kind, fs, listOf(fs.stat(File(src, "pack").path)!!), fs, dst, tempDir())
            assertEquals(kind.name, 2, n)
            Archives.extract(fs, dst, fs, File(out, "x").path, localFile = File(dst), tmpDir = tempDir())
            assertArrayEquals(kind.name, a, File(out, "x/pack/a.bin").readBytes())
            assertArrayEquals(kind.name, b, File(out, "x/pack/中文目录/b.txt").readBytes())
            assertTrue(kind.name, File(out, "x/pack/empty").isDirectory)
        }
    }

    @Test fun archiveBaseName() {
        assertEquals("my.photos", Archives.baseName("my.photos.zip"))
        assertEquals("src-1.0", Archives.baseName("src-1.0.tar.gz"))
        assertEquals(".config", Archives.baseName(".config.zip"))
    }
}
