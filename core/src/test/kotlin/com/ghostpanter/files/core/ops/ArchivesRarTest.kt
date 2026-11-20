package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.LocalFs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RAR extraction has to cover both container generations.
 *
 * junrar 7.5.5 understood RAR 1.5–4.x only and answered every RAR5 archive with
 * `UnsupportedRarV5Exception`, which is what the old "RAR5 detected, convert it with 7-Zip" dialog
 * reported. junrar 8.x ships a real rar5 package (Rar5FileHeaderReader, Rar5Crypt), so both are
 * handled in one code path now.
 *
 * The fixtures are copied from the junrar test suite so they are genuine archives, not files that
 * merely carry the .rar extension.
 */
class ArchivesRarTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixture(name: String): File {
        val f = File(tmp.root, name)
        val res = javaClass.getResourceAsStream("/$name")
        assertNotNull("missing test fixture $name", res)
        res!!.use { input -> f.outputStream().use { input.copyTo(it) } }
        return f
    }

    private fun extract(name: String, password: String? = null): File {
        val src = fixture(name)
        val out = File(tmp.root, "out-" + name.removeSuffix(".rar"))
        // A LocalFs on an absolute path is the same backend the app uses for device storage.
        val fs = LocalFs()
        Archives.extract(
            fs, src.absolutePath, fs, out.absolutePath,
            localFile = src, tmpDir = tmp.root, password = password,
        )
        return out
    }

    @Test
    fun rar5ExtractsEveryEntry() {
        val out = extract("rar5-basic.rar")
        // The fixtures store CRLF (7 bytes each), so compare the raw bytes rather than lines.
        assertEquals("file1\r\n", File(out, "FILE1.TXT").readText())
        assertEquals("file2\r\n", File(out, "FILE2.TXT").readText())
    }

    @Test
    fun rar5CompressedPayloadIsByteExact() {
        val out = extract("rar5-compressed.rar")
        val payload = File(out, "payload.bin")
        assertTrue("payload.bin missing", payload.isFile)
        // 2000 bytes of repeating pseudo-random pattern: the length alone catches a truncated read.
        assertEquals(2000L, payload.length())
    }

    @Test
    fun rar4StillExtracts() {
        val out = extract("rar4-basic.rar")
        assertEquals("file1\r\n", File(out, "FILE1.TXT").readText())
        assertEquals("file2\r\n", File(out, "FILE2.TXT").readText())
    }

    @Test
    fun encryptedRarWithoutPasswordAsksForItInsteadOfFailingOpaquely() {
        val e = assertThrows(FsException::class.java) { extract("rar5-password.rar") }
        val msg = e.message.orEmpty()
        assertTrue("expected a password hint, got: $msg", msg.contains("密码") || msg.contains("password"))
    }

    @Test
    fun encryptedRarExtractsWithTheRightPassword() {
        val out = extract("rar5-password.rar", password = "junrar")
        // This fixture stores LF only (6 bytes), unlike the two-file samples above.
        assertEquals("file1\n", File(out, "file1.txt").readText())
    }
}
