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
import org.junit.Assume.assumeTrue
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
        assertTrue(isLanAddress("::ffff:192.168.31.246"))
        assertTrue(isLanAddress("/[fe80::1%wlan0]"))
        assertFalse(isLanAddress("::ffff:8.8.8.8"))
    }

    @Test fun smbHostedRoundTripSmb2WithSmbclient() {
        assumeTrue("smbclient not installed", ProcessBuilder("which", "smbclient").start().waitFor() == 0)
        val port = freePort()
        val shareRoot = java.io.File(root, "smb-share").also { it.mkdirs() }
        java.io.File(shareRoot, "hello.txt").writeText("ghost-smb2")
        hub.putConfig(
            hub.defaultConfig(HostedKind.SMB).copy(
                port = port,
                password = "secret",
                username = "ghost",
                rootPath = shareRoot.absolutePath,
                anonymous = false,
            ),
        )
        val st = hub.start(HostedKind.SMB)
        assertTrue(st.error ?: "ok", st.running)
        // Primary verification via smbclient SMB2.1 (smbj may hang on optional IOCTLs).
        val outFile = java.io.File("/tmp/gf-smb2-hello.txt").also { it.delete() }
        val putSrc = java.io.File("/tmp/gf-smb2-put.txt").also { it.writeText("ping") }
        val smbclient = listOf(
            "smbclient", "//127.0.0.1/share", "-p", port.toString(),
            "-m", "SMB2",
            "--option=client min protocol=SMB2_02",
            "--option=client max protocol=SMB2_10",
            "-U", "ghost%secret",
            "-c", "ls; get hello.txt ${outFile.absolutePath}; put ${putSrc.absolutePath} from-client.txt",
        )
        val proc = ProcessBuilder(smbclient).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        assertTrue("smbclient SMB2 failed ($code): $out", code == 0)
        assertEquals("ghost-smb2", outFile.readText())
        assertEquals("ping", java.io.File(shareRoot, "from-client.txt").readText())
        hub.stop(HostedKind.SMB)
        assertFalse(hub.status(HostedKind.SMB).running)
    }

    @Test fun randomPasswordLength() {
        assertEquals(12, randomPassword().length)
        assertTrue(randomPassword() != randomPassword())
    }

    @Test fun byteRanges() {
        assertEquals(10L to 99L, parseByteRange("bytes=10-", 100))
        assertEquals(10L to 19L, parseByteRange("bytes=10-19", 100))
        assertEquals(90L to 99L, parseByteRange("bytes=-10", 100))
        assertEquals(0L to 99L, parseByteRange("bytes=-500", 100))
        assertEquals(null, parseByteRange("bytes=100-", 100))
        assertEquals(null, parseByteRange("bytes=5-2", 100))
    }

    private fun startDav(): Int {
        val port = freePort()
        hub.putConfig(hub.defaultConfig(HostedKind.WEBDAV).copy(port = port, password = "secret", username = "ghost"))
        assertTrue(hub.start(HostedKind.WEBDAV).running)
        return port
    }

    private val ok = okhttp3.OkHttpClient()

    private fun http(port: Int, method: String, path: String, headers: Map<String, String> = emptyMap(), body: ByteArray? = null): Pair<Int, ByteArray> {
        val b = okhttp3.Request.Builder().url("http://127.0.0.1:$port$path")
            .header("Authorization", okhttp3.Credentials.basic("ghost", "secret"))
        headers.forEach { (k, v) -> b.header(k, v) }
        val rb = body?.let { okhttp3.RequestBody.create(null, it) } ?: if (method in setOf("PUT", "MOVE", "COPY")) okhttp3.RequestBody.create(null, ByteArray(0)) else null
        b.method(method, rb)
        ok.newCall(b.build()).execute().use { r -> return r.code to (r.body?.bytes() ?: ByteArray(0)) }
    }

    @Test fun webdavPlusInNameAndRangesAndSelfMove() {
        val port = startDav()
        java.io.File(root, "a+b.txt").writeText("0123456789")
        val (c1, b1) = http(port, "GET", "/a+b.txt")
        assertEquals(200, c1); assertEquals("0123456789", String(b1))
        val (c2, b2) = http(port, "GET", "/a%2Bb.txt", mapOf("Range" to "bytes=-3"))
        assertEquals(206, c2); assertEquals("789", String(b2))
        assertEquals(416, http(port, "GET", "/a+b.txt", mapOf("Range" to "bytes=50-")).first)
        // MOVE onto itself must not delete the file
        val (c3, _) = http(port, "MOVE", "/a+b.txt", mapOf("Destination" to "http://127.0.0.1:$port/a%2Bb.txt"))
        assertEquals(403, c3)
        assertEquals("0123456789", java.io.File(root, "a+b.txt").readText())
        // browser listing
        val (c4, html) = http(port, "GET", "/")
        assertEquals(200, c4); assertTrue(String(html).contains("a%2Bb.txt"))
    }

    @Test fun webdavUnauthorizedPutKeepsServerUsable() {
        val port = startDav()
        val c = java.net.URL("http://127.0.0.1:$port/x.bin").openConnection() as java.net.HttpURLConnection
        c.requestMethod = "PUT"; c.doOutput = true
        c.outputStream.use { it.write(ByteArray(100_000)) }
        assertEquals(401, c.responseCode)
        assertEquals(201, http(port, "PUT", "/y.bin", body = "hi".toByteArray()).first)
        assertEquals("hi", java.io.File(root, "y.bin").readText())
    }
}
