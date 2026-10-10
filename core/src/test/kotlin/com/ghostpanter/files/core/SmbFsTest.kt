package com.ghostpanter.files.core

import com.ghostpanter.files.core.lan.SmbAuthRequiredException
import com.ghostpanter.files.core.SmbLogin
import com.ghostpanter.files.core.lan.SmbShares
import com.ghostpanter.files.core.transfer.JobState
import com.ghostpanter.files.core.transfer.Loc
import com.ghostpanter.files.core.transfer.TransferConfig
import com.ghostpanter.files.core.transfer.TransferEngine
import com.ghostpanter.files.core.transfer.TransferOp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Integration tests against a real Samba server. They run only when GF_SMB_PORT is set, e.g.
 * smbd on 127.0.0.1:4445 with shares public (guest, rw, path = GF_SMB_PUBLIC_DIR), media (guest),
 * secret (user box / GF_SMB_PASSWORD) and hidden$; see scripts/test-samba.
 */
class SmbFsTest {
    private val port = System.getenv("GF_SMB_PORT")?.toIntOrNull()
    private val publicDir = System.getenv("GF_SMB_PUBLIC_DIR")
    private val password = System.getenv("GF_SMB_PASSWORD") ?: ""

    private fun cfg(path: String = "public", user: String = "", pass: String = "") =
        ServerConfig("t", "t", Protocol.SMB, "127.0.0.1", port ?: 445, user, pass, path)

    @Test fun contract() {
        assumeTrue(port != null)
        SmbFs(cfg()).use { FsContract.run(it, "/public") }
    }

    @Test fun enumeratesSharesAsGuestAndFiltersDollar() {
        assumeTrue(port != null)
        val l = SmbShares.list("127.0.0.1", port!!)
        assertTrue(l.login == SmbLogin.ANONYMOUS || l.login == SmbLogin.GUEST)
        val all = l.shares.map { it.name }
        assertTrue(all.containsAll(listOf("public", "media", "secret", "hidden$", "IPC$")))
        assertEquals(listOf("media", "public", "secret"), l.visible.map { it.name })
        assertEquals("媒体", l.visible.first { it.name == "media" }.remark)
    }

    @Test fun enumeratesWithCredentialsAndRejectsBadPassword() {
        assumeTrue(port != null && password.isNotEmpty())
        val l = SmbShares.list("127.0.0.1", port!!, "box", password)
        assertEquals(SmbLogin.USER, l.login)
        assertTrue(l.visible.any { it.name == "secret" })
        val e = runCatching { SmbShares.list("127.0.0.1", port!!, "box", "wrong-password") }.exceptionOrNull()
        assertTrue("$e", e is SmbAuthRequiredException)
    }

    @Test fun rootListsEnumeratedSharesWhenNoneConfigured() {
        assumeTrue(port != null)
        SmbFs(cfg(path = "")).use { fs ->
            assertEquals(listOf("media", "public", "secret"), fs.list("/").map { it.name }.sorted())
        }
    }

    /** Regression for the v0.1.0 device report: SMB → local MOVE of a > 2 GiB file kept retrying at 100 %. */
    @Test fun moveFileLargerThan2GiBFromSmbToLocal() = runBlocking {
        assumeTrue(port != null && publicDir != null && System.getenv("GF_SMB_BIG") != null)
        val size = (2L shl 30) + 123_457 // 2 GiB + odd tail: crosses the Int range
        val src = File(publicDir, "big-move.bin")
        RandomAccessFile(src, "rw").use { it.setLength(size); it.seek(size - 4); it.write(byteArrayOf(1, 2, 3, 4)) }
        val out = tempDir("big")
        val smb = SmbFs(cfg())
        val local = LocalFs()
        val engine = TransferEngine({ if (it == smb.id) smb else local }, TransferConfig(maxRetries = 1))
        val id = engine.enqueue(TransferOp.MOVE, listOf(Loc(smb.id, "/public/big-move.bin")), Loc(local.id, out.path))
        val s = withTimeout(600_000) { engine.await(id) }
        val dst = File(out, "big-move.bin")
        try {
            assertEquals(s.toString(), JobState.COMPLETED, s.state)
            assertEquals(0, s.retries)
            assertEquals(size, dst.length())
            assertFalse("source must be deleted after move", src.exists())
            RandomAccessFile(dst, "r").use { f -> f.seek(size - 4); val b = ByteArray(4); f.readFully(b); assertEquals(listOf<Byte>(1, 2, 3, 4), b.toList()) }
        } finally {
            dst.delete(); src.delete(); smb.close()
        }
    }
}
