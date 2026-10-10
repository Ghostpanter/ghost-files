package com.ghostpanter.files.core

import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

class FtpFsTest {
    private lateinit var server: FtpServer
    private var port = 0
    private val home = tempDir("ftp")

    @Before fun start() {
        port = ServerSocket(0).use { it.localPort }
        val f = FtpServerFactory()
        f.addListener("default", ListenerFactory().apply { this.port = this@FtpFsTest.port }.createListener())
        val user = BaseUser().apply {
            name = "ghost"; password = "pw"; homeDirectory = home.path
            authorities = listOf<Authority>(WritePermission())
        }
        f.userManager.save(user)
        server = f.createServer()
        server.start()
    }

    @After fun stop() { server.stop() }

    private fun cfg(pass: String = "pw") = ServerConfig("t", "ftp", Protocol.FTP, "127.0.0.1", port, "ghost", pass)

    @Test fun contract() = FtpFs(cfg()).use { FsContract.run(it, "/") }

    @Test fun badPasswordIsReported() {
        val e = runCatching { FtpFs(cfg("nope")).list("/") }.exceptionOrNull()
        assertTrue(e is FsException && e.message!!.contains("登录失败"))
    }

    @Test fun listingWhileTransferStreamIsOpenDoesNotBlock() {
        FtpFs(cfg()).use { fs ->
            fs.openWrite("/a.bin").use { it.write(ByteArray(500_000)) }
            val input = fs.openRead("/a.bin")
            input.read(ByteArray(1000))
            // second pooled connection serves the listing while the first is mid-transfer
            val t = System.currentTimeMillis()
            assertEquals(1, fs.list("/").size)
            assertTrue(System.currentTimeMillis() - t < 5000)
            input.close() // aborted mid-transfer: connection is dropped, not waited on
            assertEquals(500_000L, fs.stat("/a.bin")!!.size)
        }
    }
}
