package com.ghostpanter.files.core.server

import com.ghostpanter.files.core.FsContract
import com.ghostpanter.files.core.FtpFs
import com.ghostpanter.files.core.MemoryHostKeyStore
import com.ghostpanter.files.core.Protocol
import com.ghostpanter.files.core.ServerConfig
import com.ghostpanter.files.core.SftpFs
import com.ghostpanter.files.core.WebDavFs
import com.ghostpanter.files.core.tempDir
import net.schmizz.sshj.DefaultConfig
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.net.ServerSocket
import java.security.Security

class HostedServersTest {
    private lateinit var hub: HostedServerHub
    private lateinit var root: java.io.File
    private lateinit var data: java.io.File

    companion object {
        @BeforeClass @JvmStatic fun providers() {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    @Before fun setUp() {
        root = tempDir("hosted-root")
        data = tempDir("hosted-data")
        hub = HostedServerHub(data) { root.absolutePath }
    }

    @After fun tearDown() = hub.stopAll()

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    @Test fun ftpClientRoundTrip() {
        val port = freePort()
        hub.putConfig(hub.defaultConfig(HostedKind.FTP).copy(port = port, password = "secret", username = "ghost"))
        val st = hub.start(HostedKind.FTP)
        assertTrue(st.error ?: "ok", st.running)
        val cfg = ServerConfig("t", "ftp", Protocol.FTP, "127.0.0.1", port, "ghost", "secret")
        FtpFs(cfg).use { FsContract.run(it, "/") }
        hub.stop(HostedKind.FTP)
        assertFalse(hub.status(HostedKind.FTP).running)
    }

    @Test fun sftpClientRoundTrip() {
        val port = freePort()
        hub.putConfig(hub.defaultConfig(HostedKind.SFTP).copy(port = port, password = "secret", username = "ghost"))
        val st = hub.start(HostedKind.SFTP)
        assertTrue(st.error ?: "ok", st.running)
        val cfg = ServerConfig("t", "sftp", Protocol.SFTP, "127.0.0.1", port, "ghost", "secret")
        SftpFs(cfg, MemoryHostKeyStore()) { DefaultConfig() }.use { FsContract.run(it, "/") }
    }

    @Test fun webdavClientRoundTrip() {
        val port = freePort()
        hub.putConfig(hub.defaultConfig(HostedKind.WEBDAV).copy(port = port, password = "secret", username = "ghost"))
        val st = hub.start(HostedKind.WEBDAV)
        assertTrue(st.error ?: "ok", st.running)
        val cfg = ServerConfig("t", "dav", Protocol.WEBDAV, "127.0.0.1", port, "ghost", "secret")
        WebDavFs(cfg).use { FsContract.run(it, "/") }
    }

    @Test fun lanOnlyRejectsNonLanWhenForced() {
        // 127.0.0.1 is LAN (loopback) so clients still work; just assert helper.
        assertTrue(isLanAddress("127.0.0.1"))
        assertTrue(isLanAddress("192.168.1.5"))
        assertTrue(isLanAddress("10.0.0.2"))
        assertFalse(isLanAddress("8.8.8.8"))
        assertFalse(isLanAddress("1.1.1.1"))
    }

    @Test fun smbReportsRootRequired() {
        val st = hub.start(HostedKind.SMB)
        assertFalse(st.running)
        assertTrue(st.error!!.contains("Root"))
    }

    @Test fun randomPasswordLength() {
        assertEquals(12, randomPassword().length)
        assertTrue(randomPassword() != randomPassword())
    }
}
