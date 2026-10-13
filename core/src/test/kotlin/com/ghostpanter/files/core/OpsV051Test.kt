package com.ghostpanter.files.core

import com.ghostpanter.files.core.ops.ArchiveTest
import com.ghostpanter.files.core.ops.Archives
import com.ghostpanter.files.core.ops.FileSplit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OpsV051Test {
    private val fs = LocalFs()
    private fun tempDir(): File = File.createTempFile("gf51", "d").also { it.delete(); it.mkdirs() }

    @Test fun splitAndMergeRoundTrip() {
        val d = tempDir()
        val raw = ByteArray(350_000) { (it % 251).toByte() }
        val src = File(d, "big.bin").also { it.writeBytes(raw) }
        val parts = FileSplit.split(fs, src.path, 100_000)
        assertTrue(parts.size >= 3)
        val dest = File(d, "out.bin").path
        val n = FileSplit.merge(fs, parts.first(), dest)
        assertEquals(raw.size.toLong(), n)
        assertArrayEquals(raw, File(dest).readBytes())
    }

    @Test fun archiveTestZipOk() {
        val d = tempDir()
        val a = File(d, "a.txt").also { it.writeText("hello") }
        val zip = File(d, "t.zip")
        Archives.compressZip(fs, listOf(fs.stat(a.path)!!), fs, zip.path)
        val r = ArchiveTest.test(fs, zip.path, zip, d)
        assertTrue(r.ok)
        assertTrue(r.checked >= 1)
    }
}
