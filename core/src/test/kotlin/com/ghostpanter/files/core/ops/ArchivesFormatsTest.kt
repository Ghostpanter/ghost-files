package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.LocalFs
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * Every "common" container the app advertises in [Archives.Kind] must round-trip real archives,
 * not just the ones a single library wrote.
 *
 * The fixtures under `resources/fmt` were produced by the platform tools (python zipfile/tarfile,
 * gzip, bzip2, xz) rather than by commons-compress, so a passing test means the reader agrees with
 * the rest of the world. The 7z and encrypted samples cannot be produced without 7-Zip, so those
 * two are generated in-test with commons-compress/zip4j and only prove the read path.
 */
class ArchivesFormatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        // Keep the original file name: single-file decompressors name their output after it.
        val dir = File(tmp.root, "fixtures").apply { mkdirs() }
        val f = File(dir, name)
        val res = javaClass.getResourceAsStream("/fmt/$name")
        assertNotNull("missing test fixture $name", res)
        res!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun extract(name: String, password: String? = null): File {
        val src = fixture(name)
        val out = File(tmp.root, "out-" + name.replace('.', '_'))
        val fs = LocalFs()
        Archives.extract(
            fs, src.absolutePath, fs, out.absolutePath,
            localFile = src, tmpDir = tmp.root, password = password,
        )
        return out
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun zipExtractsUtf8NamesAndStoredEntries() {
        val out = extract("sample.zip")
        assertEquals("hello ghost\n", File(out, "hello.txt").readText())
        assertEquals("中文内容\n", File(out, "中文.txt").readText())
        assertEquals("nested\n", File(out, "sub/nested.txt").readText())
        assertEquals("stored ok\n", File(out, "stored.txt").readText())
        assertEquals(200 * 1024L, File(out, "bin/blob.bin").length())
        assertTrue("empty directory entry missing", File(out, "empty").isDirectory)
    }

    @Test
    fun zipWithoutUtf8FlagFallsBackToGbk() {
        val out = extract("sample-gbk.zip")
        assertEquals("GBK 名称\n", File(out, "目录/文件.txt").readText())
        assertEquals("hi\n", File(out, "hello.txt").readText())
    }

    @Test
    fun tarLinksAreSkippedInsteadOfBecomingEmptyFiles() {
        // Source tarballs routinely carry symlinks and hard links. VirtualFs cannot create them, and
        // silently writing a 0-byte file next to the real one looks like corruption.
        val out = extract("sample-links.tar")
        assertEquals("#!/bin/sh\necho hi\n", File(out, "bin/run.sh").readText())
        assertFalse("symlink leaked as a file", File(out, "bin/link").exists())
        assertFalse("hard link leaked as a file", File(out, "bin/hard").exists())
    }

    @Test
    fun onlyWrappedFilesCountAsSingleFile() {
        for (n in listOf("a.gz", "a.xz", "a.bz2", "a.zst", "a.lz4", "SINGLE.TXT.GZ")) {
            assertTrue(n, Archives.isSingleFile(n))
        }
        for (n in listOf("a.tar.gz", "a.tgz", "a.zip", "a.7z", "a.tar", "a.tar.zst")) {
            assertFalse(n, Archives.isSingleFile(n))
        }
    }

    @Test
    fun tarExtracts() {
        val out = extract("sample.tar")
        assertEquals("hello ghost\n", File(out, "hello.txt").readText())
        assertEquals("中文内容\n", File(out, "中文.txt").readText())
        assertEquals("nested\n", File(out, "sub/nested.txt").readText())
        assertEquals(200 * 1024L, File(out, "bin/blob.bin").length())
    }

    @Test
    fun compressedTarballsExtract() {
        for (name in listOf("sample.tar.gz", "sample.tar.bz2", "sample.tar.xz")) {
            val out = extract(name)
            assertEquals("$name: hello.txt", "hello ghost\n", File(out, "hello.txt").readText())
            assertEquals("$name: 中文.txt", "中文内容\n", File(out, "中文.txt").readText())
            assertEquals("$name: nested", "nested\n", File(out, "sub/nested.txt").readText())
            assertEquals("$name: blob size", 200 * 1024L, File(out, "bin/blob.bin").length())
        }
    }

    @Test
    fun singleFileCompressorsDropTheSuffix() {
        val expected = "single member\n".repeat(100)
        for (name in listOf("single.txt.gz", "single.txt.bz2", "single.txt.xz")) {
            val out = extract(name)
            val produced = File(out, "single.txt")
            assertTrue("$name: expected single.txt, got ${out.list()?.joinToString()}", produced.isFile)
            assertEquals("$name", sha256(expected.toByteArray()), sha256(produced.readBytes()))
        }
    }

    @Test
    fun sevenZipExtractsDirectoriesAndNestedEntries() {
        val src = File(tmp.root, "generated.7z")
        val payload = ByteArray(64 * 1024) { (it * 31 and 0xff).toByte() }
        SevenZOutputFile(src).use { z ->
            fun put(name: String, data: ByteArray, dir: Boolean = false) {
                val e = SevenZArchiveEntry()
                e.name = name
                e.isDirectory = dir
                if (!dir) e.size = data.size.toLong()
                z.putArchiveEntry(e)
                if (!dir) z.write(data)
                z.closeArchiveEntry()
            }
            put("empty/", ByteArray(0), dir = true)
            put("hello.txt", "hello ghost\n".toByteArray())
            put("中文.txt", "中文内容\n".toByteArray())
            put("bin/blob.bin", payload)
        }
        val out = File(tmp.root, "out-7z")
        val fs = LocalFs()
        Archives.extract(fs, src.absolutePath, fs, out.absolutePath, localFile = src, tmpDir = tmp.root)
        assertEquals("hello ghost\n", File(out, "hello.txt").readText())
        assertEquals("中文内容\n", File(out, "中文.txt").readText())
        assertEquals(sha256(payload), sha256(File(out, "bin/blob.bin").readBytes()))
        assertTrue(File(out, "empty").isDirectory)
    }

    @Test
    fun encryptedSevenZipWithoutPasswordAsksForIt() {
        val src = File(tmp.root, "locked.7z")
        SevenZOutputFile(src, "s3cret".toCharArray()).use { z ->
            val e = SevenZArchiveEntry()
            e.name = "hello.txt"
            val data = "hello ghost\n".toByteArray()
            e.size = data.size.toLong()
            z.putArchiveEntry(e)
            z.write(data)
            z.closeArchiveEntry()
        }
        val out = File(tmp.root, "out-7z-locked")
        val fs = LocalFs()
        val e = assertThrows(FsException::class.java) {
            Archives.extract(fs, src.absolutePath, fs, out.absolutePath, localFile = src, tmpDir = tmp.root)
        }
        assertTrue("expected a password hint, got: ${e.message}", (e.message ?: "").contains("密码"))

        // ... and the same archive still opens with it.
        Archives.extract(fs, src.absolutePath, fs, out.absolutePath, localFile = src, tmpDir = tmp.root, password = "s3cret")
        assertEquals("hello ghost\n", File(out, "hello.txt").readText())
    }

    @Test
    fun sevenZipWrittenBySevenZipItselfExtractsInEveryMethod() {
        // The fixtures hold the same payload compressed with LZMA2 and BZip2 inside a 7z container;
        // they come from the 7-Zip test corpus, not from commons-compress. PPMd has its own test:
        // commons-compress ships no PPMd decoder, so it must at least fail with a clear message.
        val dirs = listOf("real-lzma2.7z", "real-bzip2.7z").map { extract(it) }
        fun listing(d: File) = d.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(d).invariantSeparatorsPath }.sorted().toList()
        val base = listing(dirs[0])
        assertTrue("archive appears empty", base.isNotEmpty())
        for (i in 1 until dirs.size) {
            assertEquals("entry list differs for ${dirs[i].name}", base, listing(dirs[i]))
            for (rel in base) {
                assertEquals(
                    "content differs for $rel in ${dirs[i].name}",
                    sha256(File(dirs[0], rel).readBytes()),
                    sha256(File(dirs[i], rel).readBytes()),
                )
            }
        }
    }

    @Test
    fun ppmdSevenZipFailsWithAnActionableMessage() {
        // 7-Zip's PPMd codec has no Java implementation in commons-compress (COMPRESS-288).
        val e = assertThrows(FsException::class.java) { extract("real-ppmd.7z") }
        val msg = e.message.orEmpty()
        assertTrue("should name the missing codec, got: $msg", msg.contains("PPMd") || msg.contains("LZMA2"))
        assertFalse("must not blame the password", msg.contains("密码"))
    }

    @Test
    fun tinySevenZipWithDisabledHeaderCompressionExtracts() {
        val out = extract("real-hello.7z")
        assertTrue("expected at least one extracted file", out.walkTopDown().any { it.isFile })
    }

    @Test
    fun traditionalPkcryptZipAsksForPasswordThenOpens() {
        val e = assertThrows(FsException::class.java) { extract("locked.zip") }
        assertTrue("expected a password hint, got: ${e.message}", (e.message ?: "").contains("密码"))
        assertTrue("must be retryable by the UI", e is ArchivePasswordException)

        val out = extract("locked.zip", password = "s3cret")
        assertEquals("inside the encrypted zip\n", File(out, "secret.txt").readText())
    }

    @Test
    fun encryptedZipIsRecognisedAsSuch() {
        val plain = File(tmp.root, "plain.txt").apply { writeText("zip secret\n") }
        val src = File(tmp.root, "locked.zip")
        net.lingala.zip4j.ZipFile(src, "s3cret".toCharArray()).run {
            addFile(plain, net.lingala.zip4j.model.ZipParameters().apply {
                isEncryptFiles = true
                setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.ZIP_STANDARD)
            })
        }
        val out = File(tmp.root, "out-zip-locked")
        val fs = LocalFs()
        val e = assertThrows(FsException::class.java) {
            Archives.extract(fs, src.absolutePath, fs, out.absolutePath, localFile = src, tmpDir = tmp.root)
        }
        assertTrue("expected a password hint, got: ${e.message}", (e.message ?: "").contains("密码"))

        Archives.extract(fs, src.absolutePath, fs, out.absolutePath, localFile = src, tmpDir = tmp.root, password = "s3cret")
        assertEquals("zip secret\n", File(out, "plain.txt").readText())
    }
}
