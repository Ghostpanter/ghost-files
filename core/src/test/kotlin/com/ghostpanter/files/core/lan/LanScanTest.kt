package com.ghostpanter.files.core.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class LanScanTest {

    // ------------------------------------------------------------ subnet

    @Test fun subnetCapsLargeNetworksToOwn24() {
        val h = Subnet.hosts("10.1.2.77", 16)
        assertEquals(253, h.size)
        assertEquals("10.1.2.1", h.first()); assertEquals("10.1.2.254", h.last())
        assertFalse("10.1.2.77" in h)
        assertEquals(listOf("192.168.1.1", "192.168.1.2"), Subnet.hosts("192.168.1.3", 30) + emptyList())
        assertTrue(Subnet.isPrivate("192.168.31.246")); assertFalse(Subnet.isPrivate("8.8.8.8"))
    }

    // ------------------------------------------------------------ port sweep

    @Test fun sweepFindsOpenPortsOnLoopback() = runBlocking {
        val a = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val b = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val closed = ServerSocket(0).let { val p = it.localPort; it.close(); p }
        val found = Collections.synchronizedList(ArrayList<Pair<String, Int>>())
        PortSweep.sweep(listOf("127.0.0.1"), listOf(a.localPort, closed, b.localPort), timeoutMs = 300) { h, p -> found.add(h to p) }
        a.close(); b.close()
        assertEquals(setOf("127.0.0.1" to a.localPort, "127.0.0.1" to b.localPort), found.toSet())
    }

    @Test fun sweepRespectsConcurrencyLimitAndCoversEveryProbe() = runBlocking {
        val inFlight = AtomicInteger(); val maxInFlight = AtomicInteger(); val calls = AtomicInteger()
        val hosts = Subnet.hosts("192.168.5.10", 24)
        var progressTotal = 0
        PortSweep.sweep(
            hosts, PortSweep.DEFAULT_PORTS, concurrency = 16,
            connector = { h, p, _ ->
                val n = inFlight.incrementAndGet(); maxInFlight.accumulateAndGet(n, ::maxOf); calls.incrementAndGet()
                Thread.sleep(1)
                inFlight.decrementAndGet()
                h.endsWith(".20") && p == 445
            },
            onProgress = { _, total -> progressTotal = total },
        ) { h, p -> assertEquals("192.168.5.20" to 445, h to p) }
        assertEquals(hosts.size * PortSweep.DEFAULT_PORTS.size, calls.get())
        assertEquals(calls.get(), progressTotal)
        assertTrue("max in flight ${maxInFlight.get()}", maxInFlight.get() <= 16)
    }

    @Test fun sweepIsCancellable() = runBlocking {
        val calls = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            PortSweep.sweep(Subnet.hosts("10.0.0.5", 24), PortSweep.DEFAULT_PORTS, concurrency = 4,
                connector = { _, _, _ -> calls.incrementAndGet(); Thread.sleep(20); false }) { _, _ -> }
        }
        delay(150)
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue("stopped early: ${calls.get()}", calls.get() < 200)
    }

    // ------------------------------------------------------------ probes

    @Test fun probeReadsFtpAndSshBanners() {
        fun bannerServer(text: String): ServerSocket {
            val ss = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
            thread(isDaemon = true) { runCatching { ss.accept().use { it.getOutputStream().write(text.toByteArray()); Thread.sleep(200) } } }
            return ss
        }
        val ftp = bannerServer("220 NAS FTP server ready\r\n")
        val ftpSvc = ServiceProbe.banner("127.0.0.1", ftp.localPort, 1000)
        assertEquals("220 NAS FTP server ready", ftpSvc)
        val ssh = bannerServer("SSH-2.0-OpenSSH_9.6\r\n")
        assertTrue(ServiceProbe.banner("127.0.0.1", ssh.localPort, 1000)!!.startsWith("SSH-2.0"))
        ftp.close(); ssh.close()
    }

    @Test fun httpProbeDetectsDavHeader() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("DAV", "1, 2").setHeader("Server", "nginx").setResponseCode(200))
        server.enqueue(MockResponse().setHeader("Server", "lighttpd").setResponseCode(200))
        server.start()
        val dav = ServiceProbe.http("127.0.0.1", server.port, tls = false, timeoutMs = 2000)
        assertEquals(LanServiceKind.WEBDAV, dav!!.kind)
        assertEquals("nginx", dav.detail)
        assertEquals("OPTIONS", server.takeRequest().method)
        val plain = ServiceProbe.http("127.0.0.1", server.port, tls = false, timeoutMs = 2000)
        assertEquals(LanServiceKind.HTTP, plain!!.kind)
        server.shutdown()
    }

    @Test fun httpHeadParserUsesAllowPropfind() {
        val raw = "HTTP/1.1 200 OK\r\nAllow: GET, PROPFIND, MKCOL\r\n\r\n".byteInputStream()
        assertEquals(LanServiceKind.WEBDAVS, ServiceProbe.parseHttpHead(raw, 5006, tls = true)!!.kind)
        assertNull(ServiceProbe.parseHttpHead("SSH-2.0-x\r\n".byteInputStream(), 80, false))
    }

    // ------------------------------------------------------------ NetBIOS

    @Test fun nbstatQueryEncodesWildcardName() {
        val q = NetBios.query(0x1234)
        assertEquals(50, q.size)
        assertEquals(0x12, q[0].toInt()); assertEquals(0x34, q[1].toInt())
        assertEquals(1, q[5].toInt())
        assertEquals(0x20, q[12].toInt())
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(q, 13, 32, Charsets.US_ASCII))
        assertArrayEquals(byteArrayOf(0, 0, 0x21, 0, 1), q.copyOfRange(45, 50))
    }

    private fun nbstatResponse(vararg names: Triple<String, Int, Boolean>, mac: ByteArray = byteArrayOf(0x52, 0x54, 0, 0x12, 0x34, 0x56)): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0x12, 0x34, 0x84.toByte(), 0x00, 0, 0, 0, 1, 0, 0, 0, 0))
        o.write(NetBios.query(0).copyOfRange(12, 46)) // the encoded RR name
        o.write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0))
        val rd = ByteArrayOutputStream()
        rd.write(names.size)
        for ((n, suffix, group) in names) {
            rd.write(n.padEnd(15, ' ').toByteArray(Charsets.ISO_8859_1)); rd.write(suffix)
            rd.write(if (group) 0x84 else 0x04); rd.write(0x00)
        }
        rd.write(mac); rd.write(ByteArray(40))
        val r = rd.toByteArray()
        o.write(r.size ushr 8); o.write(r.size and 0xFF); o.write(r)
        return o.toByteArray()
    }

    @Test fun parsesNbstatAnswer() {
        val pkt = nbstatResponse(
            Triple("NAS-PC", 0x00, false), Triple("NAS-PC", 0x20, false),
            Triple("WORKGROUP", 0x00, true), Triple("WORKGROUP", 0x1e, true),
        )
        val st = NetBios.parse(pkt)!!
        assertEquals("NAS-PC", st.hostname)
        assertEquals("WORKGROUP", st.workgroup)
        assertTrue(st.isFileServer)
        assertEquals("52:54:00:12:34:56", st.mac)
        assertEquals(4, st.names.size)
    }

    @Test fun rejectsQueriesAndTruncatedPackets() {
        assertNull(NetBios.parse(NetBios.query(1)))
        val pkt = nbstatResponse(Triple("X", 0x20, false))
        assertNull(NetBios.parse(pkt, 60))
    }

    @Test fun netbiosScanCollectsAnswersOverUdp() = runBlocking {
        val responder = DatagramSocket(0, InetAddress.getLoopbackAddress())
        val answer = nbstatResponse(Triple("MEDIA-BOX", 0x20, false))
        thread(isDaemon = true) {
            val buf = ByteArray(100); val p = DatagramPacket(buf, buf.size)
            responder.receive(p)
            responder.send(DatagramPacket(answer, answer.size, p.address, p.port))
        }
        val got = ArrayList<String>()
        NetBios.scan(listOf("127.0.0.1"), timeoutMs = 1500, port = responder.localPort) { ip, st -> got.add("$ip ${st.hostname}") }
        responder.close()
        assertEquals(listOf("127.0.0.1 MEDIA-BOX"), got)
    }

    // ------------------------------------------------------------ results & shares

    @Test fun resultsMergeByHostAndPreferConfirmedDav() {
        val r = LanResults()
        r.add("192.168.1.20", service = LanService(LanServiceKind.HTTP, 5005, LanSource.PORT))
        r.add("192.168.1.20", hostname = "NAS", service = LanService(LanServiceKind.SMB, 445, LanSource.PORT))
        r.add("192.168.1.3", service = LanService(LanServiceKind.FTP, 21, LanSource.PORT))
        val snap = r.add("192.168.1.20", service = LanService(LanServiceKind.WEBDAV, 5005, LanSource.MDNS, "Synology", "/dav"))
        assertEquals(listOf("192.168.1.3", "192.168.1.20"), snap.map { it.ip })
        val nas = snap[1]
        assertEquals("NAS", nas.hostname)
        assertEquals(listOf(LanServiceKind.SMB, LanServiceKind.WEBDAV), nas.services.map { it.kind })
        assertEquals("/dav", nas.services[1].path)
    }

    @Test fun shareFilterDropsAdminIpcAndPrinters() {
        val all = listOf(
            SmbShare("Media", 0, "电影"), SmbShare("ADMIN$", SmbShare.STYPE_SPECIAL, ""),
            SmbShare("C$", SmbShare.STYPE_SPECIAL or SmbShare.STYPE_DISKTREE), SmbShare("IPC$", SmbShare.STYPE_SPECIAL or SmbShare.STYPE_IPC),
            SmbShare("print", SmbShare.STYPE_PRINTQ), SmbShare("hidden$", 0), SmbShare("backup", 0), SmbShare("media", 0),
        )
        assertEquals(listOf("backup", "Media"), SmbShares.userVisible(all).map { it.name })
    }
}
