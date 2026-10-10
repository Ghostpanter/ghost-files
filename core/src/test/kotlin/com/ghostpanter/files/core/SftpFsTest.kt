package com.ghostpanter.files.core

import net.schmizz.sshj.DefaultConfig
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class SftpFsTest {
    private lateinit var sshd: SshServer
    private val home = tempDir("sftp")
    private val keyFile = File(tempDir("key"), "host.ser")

    private fun startServer() {
        sshd = SshServer.setUpDefaultServer()
        sshd.port = 0
        sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile.toPath())
        sshd.setPasswordAuthenticator { u, p, _ -> u == "ghost" && p == "pw" }
        sshd.subsystemFactories = listOf(SftpSubsystemFactory())
        sshd.fileSystemFactory = VirtualFileSystemFactory(home.toPath())
        sshd.start()
    }

    @Before fun start() = startServer()
    @After fun stop() { sshd.stop(true) }

    private fun cfg() = ServerConfig("s", "sftp", Protocol.SFTP, "127.0.0.1", sshd.port, "ghost", "pw")

    @Test fun contract() = SftpFs(cfg(), MemoryHostKeyStore()) { DefaultConfig() }.use { FsContract.run(it, "/") }

    @Test fun pinnedHostKeyMismatchIsRefused() {
        val keys = MemoryHostKeyStore()
        keys.put("127.0.0.1:${sshd.port}", "SHA256:not-the-real-key")
        val e = runCatching { SftpFs(cfg(), keys) { DefaultConfig() }.list("/") }.exceptionOrNull()
        assertTrue(e?.message, e is FsException && e.message!!.contains("主机密钥"))
    }

    @Test fun reconnectsAfterServerRestart() {
        val keys = MemoryHostKeyStore()
        SftpFs(cfg(), keys) { DefaultConfig() }.use { fs ->
            fs.list("/")
            val port = sshd.port
            sshd.stop(true)
            assertTrue(runCatching { fs.list("/") }.isFailure)
            sshd = SshServer.setUpDefaultServer()
            sshd.port = port
            sshd.keyPairProvider = SimpleGeneratorHostKeyProvider(keyFile.toPath())
            sshd.setPasswordAuthenticator { u, p, _ -> u == "ghost" && p == "pw" }
            sshd.subsystemFactories = listOf(SftpSubsystemFactory())
            sshd.fileSystemFactory = VirtualFileSystemFactory(home.toPath())
            sshd.start()
            fs.list("/") // transparently reconnected
        }
    }
}
