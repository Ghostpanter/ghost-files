package com.ghostpanter.files.core

import com.ghostpanter.files.core.transfer.JobState
import com.ghostpanter.files.core.transfer.Loc
import com.ghostpanter.files.core.transfer.TransferConfig
import com.ghostpanter.files.core.transfer.TransferEngine
import com.ghostpanter.files.core.transfer.TransferOp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FilterOutputStream
import java.io.OutputStream
import kotlin.random.Random

/**
 * Connection-stability regressions against a real Samba (scripts/test-samba). Timeouts are
 * scaled down (1.5 s instead of 30 s) so the failure modes show up in seconds.
 */
class SmbStabilityTest {
    private val port = System.getenv("GF_SMB_PORT")?.toIntOrNull()
    private val publicDir = System.getenv("GF_SMB_PUBLIC_DIR")
    private val short = NetTimeouts(connectMs = 3_000, readMs = 1_500)

    private fun smb(t: NetTimeouts = short, p: Int = port ?: 445, dedicated: Boolean = false) =
        SmbFs(ServerConfig("s", "s", Protocol.SMB, "127.0.0.1", p, path = "public"), t, dedicated)

    /**
     * TCP proxy in front of Samba that can sever every connection after a given number of
     * server->client bytes (simulates Wi-Fi drops / router resets / server-side disconnects).
     */
    private class DropProxy(private val target: Int, dropAfter: List<Long>, private val latencyMs: Long = 0) : AutoCloseable {
        private val server = java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())
        val port: Int get() = server.localPort
        private val socks = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        private val thresholds = java.util.concurrent.ConcurrentLinkedQueue(dropAfter.sorted())
        private val down = java.util.concurrent.atomic.AtomicLong()
        val drops = java.util.concurrent.atomic.AtomicInteger()
        init {
            Thread {
                while (!server.isClosed) {
                    val c = runCatching { server.accept() }.getOrNull() ?: break
                    val s = java.net.Socket("127.0.0.1", target)
                    socks += c; socks += s
                    pump(c, s, false); pump(s, c, true)
                }
            }.apply { isDaemon = true }.start()
        }
        private fun pump(a: java.net.Socket, b: java.net.Socket, countDown: Boolean) = Thread {
            val buf = ByteArray(64 * 1024)
            runCatching {
                val i = a.getInputStream(); val o = b.getOutputStream()
                while (true) {
                    val n = i.read(buf); if (n < 0) break
                    if (latencyMs > 0) Thread.sleep(latencyMs) // (kernel has no sch_netem: emulate latency here)
                    o.write(buf, 0, n)
                    if (countDown) {
                        val t = down.addAndGet(n.toLong())
                        val next = thresholds.peek()
                        if (next != null && t >= next) { thresholds.poll(); drops.incrementAndGet(); dropAll() }
                    }
                }
            }
            runCatching { a.close() }; runCatching { b.close() }
        }.apply { isDaemon = true }.start()
        fun dropAll() { for (x in socks) runCatching { x.close() }; socks.clear() }
        override fun close() { runCatching { server.close() }; dropAll() }
    }

    /** A destination whose close() blocks like Android's MediaProvider scanning a big video. */
    private class SlowCloseLocal(private val delayMs: Long) : VirtualFs by LocalFs(id = "slowlocal") {
        override val id = "slowlocal"
        override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream =
            object : FilterOutputStream(LocalFs(id = "x").openWrite(path, offset, sizeHint)) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() { super.close(); Thread.sleep(delayMs) }
            }
    }

    private fun seed(dir: String, n: Int, size: Int): List<ByteArray> {
        val d = File(publicDir, dir).apply { deleteRecursively(); mkdirs() }
        return (0 until n).map { i -> Random(i).nextBytes(size).also { File(d, "f$i.bin").writeBytes(it) } }
    }

    @Test fun idleConnectionIsStillUsable() {
        assumeTrue(port != null && publicDir != null)
        smb().use { fs ->
            fs.list("/public")
            Thread.sleep(short.readMs + 1_500L) // longer than the socket/read timeout with nothing in flight
            fs.list("/public") // must not fail with TransportException
        }
    }

    @Test fun slowDestinationCloseDoesNotKillSourceConnection() {
        assumeTrue(port != null && publicDir != null)
        val data = seed("stab-slow", 3, 3_000_000)
        val src = smb()
        val dst = SlowCloseLocal(delayMs = short.readMs + 1_500L)
        val out = tempDir("stab")
        val e = TransferEngine({ if (it == src.id) src else dst }, TransferConfig(initialBackoffMs = 50, maxBackoffMs = 100, stallTimeoutMs = 20_000))
        val id = e.enqueue(TransferOp.MOVE, listOf(Loc(src.id, "/public/stab-slow")), Loc(dst.id, out.path))
        val s = runBlocking { withTimeout(120_000) { e.await(id) } }
        src.close()
        assertEquals(s.toString(), JobState.COMPLETED, s.state)
        assertEquals("retries: ${s.lastError}", 0, s.retries)
        data.forEachIndexed { i, b -> assertTrue(File(out, "stab-slow/f$i.bin").readBytes().contentEquals(b)) }
    }

    /** Two jobs reading from the same server while the UI keeps listing it. */
    @Test fun concurrentJobsAndBrowsingStayHealthy() = runBlocking {
        assumeTrue(port != null && publicDir != null)
        seed("stab-a", 4, 4_000_000); seed("stab-b", 4, 4_000_000)
        val browse = smb(NetTimeouts())
        val local = LocalFs()
        val e = TransferEngine({ if (it == browse.id) browse else local }, TransferConfig(maxConcurrentJobs = 2, initialBackoffMs = 50, maxBackoffMs = 100))
        val outA = tempDir("a"); val outB = tempDir("b")
        val ids = listOf(
            e.enqueue(TransferOp.COPY, listOf(Loc(browse.id, "/public/stab-a")), Loc(local.id, outA.path)),
            e.enqueue(TransferOp.COPY, listOf(Loc(browse.id, "/public/stab-b")), Loc(local.id, outB.path)),
        )
        val lister = async(kotlinx.coroutines.Dispatchers.IO) { repeat(40) { browse.list("/public"); Thread.sleep(25) } }
        val res = withTimeout(180_000) { ids.map { async { e.await(it) } }.awaitAll() }
        lister.await()
        browse.close()
        res.forEach { assertEquals(it.toString(), JobState.COMPLETED, it.state); assertEquals(0, it.retries) }
    }

    /**
     * Root-cause reproduction: smbj with a socket read timeout (what v0.2.0 configured, 30 s)
     * kills the whole connection as soon as nothing arrives for that long, failing the next
     * request with TransportException -> ExecutionException -> ... exactly like the device log.
     */
    @Test fun socketTimeoutConfigReproducesTransportException() {
        assumeTrue(port != null && publicDir != null)
        val cfg = com.hierynomus.smbj.SmbConfig.builder()
            .withTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .withSoTimeout(1_500, java.util.concurrent.TimeUnit.MILLISECONDS).build()
        com.hierynomus.smbj.SMBClient(cfg).use { c ->
            val conn = c.connect("127.0.0.1", port!!)
            val ses = conn.authenticate(com.hierynomus.smbj.auth.AuthenticationContext.guest())
            val share = ses.connectShare("public") as com.hierynomus.smbj.share.DiskShare
            share.list("")
            Thread.sleep(3_000)
            val err = runCatching { share.list("") }.exceptionOrNull()
            assertTrue("expected a transport failure, got $err", err != null)
            val chain = ErrorText.chain(err!!)
            println("socket-timeout repro, next request failed with: " + chain.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" })
            // The reader thread died on SocketTimeoutException and tore the share/connection down.
            assertTrue(chain.any { it is com.hierynomus.protocol.transport.TransportException || (it.message ?: "").contains("closed") })
            assertTrue(ErrorText.stackTrace(err), SmbFs.isConnectionError(err))
        }
    }

    /** Connection severed twice in the middle of a 48 MB read: resumed in place, no engine retry. */
    @Test fun connectionDropMidTransferResumesWithoutEngineRetry() = runBlocking {
        assumeTrue(port != null && publicDir != null)
        val data = seed("stab-drop", 1, 48_000_000)[0]
        DropProxy(port!!, listOf(12_000_000L, 30_000_000L)).use { proxy ->
            val src = smb(NetTimeouts(connectMs = 3_000, readMs = 10_000), proxy.port)
            val local = LocalFs()
            val out = tempDir("drop")
            val e = TransferEngine({ if (it == src.id) src else local }, TransferConfig(initialBackoffMs = 50, maxBackoffMs = 100))
            val id = e.enqueue(TransferOp.COPY, listOf(Loc(src.id, "/public/stab-drop/f0.bin")), Loc(local.id, out.path))
            val s = withTimeout(120_000) { e.await(id) }
            src.close()
            assertEquals(s.toString(), JobState.COMPLETED, s.state)
            assertEquals(2, proxy.drops.get())
            assertEquals("retries: ${s.lastError}", 0, s.retries)
            assertTrue(File(out, "f0.bin").readBytes().contentEquals(data))
        }
    }

    /** Jobs on dedicated connections are unaffected when the browsing connection is torn down. */
    @Test fun dedicatedJobConnectionsSurviveBrowsingResets() = runBlocking {
        assumeTrue(port != null && publicDir != null)
        seed("stab-d1", 3, 6_000_000); seed("stab-d2", 3, 6_000_000)
        val browse = smb(NetTimeouts())
        val local = LocalFs()
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val e = TransferEngine(
            resolve = { if (it == browse.id) browse else local },
            config = TransferConfig(maxConcurrentJobs = 2, initialBackoffMs = 50, maxBackoffMs = 100),
            openDedicated = { if (it == browse.id) smb(NetTimeouts(), dedicated = true).also { opened.incrementAndGet() } else null },
        )
        val ids = listOf("stab-d1", "stab-d2").map { e.enqueue(TransferOp.COPY, listOf(Loc(browse.id, "/public/$it")), Loc(local.id, tempDir(it).path)) }
        val chaos = async(kotlinx.coroutines.Dispatchers.IO) { repeat(30) { browse.list("/public"); browse.reset(); Thread.sleep(20) } }
        val res = withTimeout(180_000) { ids.map { async { e.await(it) } }.awaitAll() }
        chaos.await()
        browse.close()
        assertEquals(2, opened.get())
        res.forEach { assertEquals(it.toString(), JobState.COMPLETED, it.state); assertEquals(0, it.retries) }
    }

    @Test fun errorTextUnwrapsSmbjChain() {
        val root = java.net.SocketTimeoutException("Read timed out")
        val e = com.hierynomus.protocol.transport.TransportException(
            java.util.concurrent.ExecutionException(com.hierynomus.smbj.common.SMBRuntimeException(com.hierynomus.protocol.transport.TransportException(root))),
        )
        val d = ErrorText.describe(e)
        assertTrue(d, d.contains("超时") && d.contains("SocketTimeoutException"))
        assertTrue(ErrorText.stackTrace(e).contains("Caused by: java.net.SocketTimeoutException"))
        assertTrue(SmbFs.isConnectionError(e))
    }

    /**
     * Wi-Fi-like conditions: added latency on every segment, connections severed repeatedly,
     * two parallel jobs on dedicated connections plus browsing through the same flaky link.
     */
    @Test fun latencyAndRepeatedDropsWithParallelJobs() = runBlocking {
        assumeTrue(port != null && publicDir != null)
        val a = seed("stab-la", 3, 8_000_000); val b = seed("stab-lb", 3, 8_000_000)
        DropProxy(port!!, listOf(5_000_000L, 15_000_000L, 28_000_000L, 40_000_000L), latencyMs = 3).use { proxy ->
            val t = NetTimeouts(connectMs = 3_000, readMs = 10_000)
            val browse = smb(t, proxy.port)
            val local = LocalFs()
            val e = TransferEngine(
                resolve = { if (it == browse.id) browse else local },
                config = TransferConfig(maxConcurrentJobs = 2, initialBackoffMs = 50, maxBackoffMs = 100),
                openDedicated = { if (it == browse.id) smb(t, proxy.port, dedicated = true) else null },
            )
            val outA = tempDir("la"); val outB = tempDir("lb")
            val ids = listOf(
                e.enqueue(TransferOp.MOVE, listOf(Loc(browse.id, "/public/stab-la")), Loc(local.id, outA.path)),
                e.enqueue(TransferOp.COPY, listOf(Loc(browse.id, "/public/stab-lb")), Loc(local.id, outB.path)),
            )
            val lister = async(kotlinx.coroutines.Dispatchers.IO) { repeat(20) { runCatching { browse.list("/public") }; Thread.sleep(50) } }
            val res = withTimeout(240_000) { ids.map { async { e.await(it) } }.awaitAll() }
            lister.await()
            browse.close()
            assertEquals(4, proxy.drops.get())
            res.forEach {
                assertEquals(it.toString(), JobState.COMPLETED, it.state)
                assertTrue("retries ${it.retries}: ${it.lastError}", it.retries <= 1)
            }
            a.forEachIndexed { i, d -> assertTrue(File(outA, "stab-la/f$i.bin").readBytes().contentEquals(d)) }
            b.forEachIndexed { i, d -> assertTrue(File(outB, "stab-lb/f$i.bin").readBytes().contentEquals(d)) }
            assertTrue(!File(publicDir, "stab-la/f0.bin").exists())
        }
    }

    // ---- v0.2.2: field log (router SMB on USB disk): reads time out mid-file and at EOF -----------

    private fun copyThrough(proxyPort: Int, path: String, t: NetTimeouts, cfg: TransferConfig = TransferConfig(initialBackoffMs = 50, maxBackoffMs = 100)): Pair<com.ghostpanter.files.core.transfer.JobSnapshot, File> = runBlocking {
        val src = smb(t, proxyPort)
        val local = LocalFs()
        val out = tempDir("chaos")
        val e = TransferEngine(
            resolve = { if (it == src.id) src else local }, config = cfg,
            openDedicated = { if (it == src.id) smb(t, proxyPort, dedicated = true) else null },
        )
        val id = e.enqueue(TransferOp.COPY, listOf(Loc(src.id, path)), Loc(local.id, out.path))
        val s = withTimeout(240_000) { e.await(id) }
        src.close()
        s to out
    }

    /** Responses silently stop on the transfer's connection (no RST): must reconnect fast and resume. */
    @Test fun blackHoleMidFileRecoversByFastReconnect() {
        assumeTrue(port != null && publicDir != null)
        val data = seed("stab-bh", 1, 24_000_000)[0]
        SmbChaosProxy(port!!, blackHoleAfter = 8_000_000).use { proxy ->
            val t0 = System.currentTimeMillis()
            val (s, out) = copyThrough(proxy.port, "/public/stab-bh/f0.bin", NetTimeouts(connectMs = 3_000, readMs = 2_000))
            val took = System.currentTimeMillis() - t0
            assertEquals(s.toString(), JobState.COMPLETED, s.state)
            assertEquals(1, proxy.blackHoled.get())
            assertEquals("retries: ${s.lastError}", 0, s.retries)
            assertTrue("reconnects ${s.reconnects}", s.reconnects >= 1)
            assertTrue("took $took ms", took < 20_000)
            assertTrue(File(out, "f0.bin").readBytes().contentEquals(data))
        }
    }

    /** The disk behind the server stalls READs on every connection for a while. */
    @Test fun diskStallOnReadsRecoversWithoutJobRetry() {
        assumeTrue(port != null && publicDir != null)
        val data = seed("stab-st", 1, 24_000_000)[0]
        // 25 s: longer than several read deadlines AND than the watchdog window (request 6 s + 15 s).
        SmbChaosProxy(port!!, stallAfter = 6_000_000, stallReadsMs = 25_000).use { proxy ->
            val (s, out) = copyThrough(
                proxy.port, "/public/stab-st/f0.bin", NetTimeouts(connectMs = 3_000, readMs = 2_000),
                TransferConfig(initialBackoffMs = 50, maxBackoffMs = 100, stallTimeoutMs = 1_000),
            )
            assertEquals(s.toString(), JobState.COMPLETED, s.state)
            assertEquals("retries: ${s.lastError}", 0, s.retries)
            assertTrue("reconnects ${s.reconnects}", s.reconnects >= 1)
            assertTrue(File(out, "f0.bin").readBytes().contentEquals(data))
        }
    }

    /** Server never answers a READ at/after EOF (field log, 3rd failure at 5205554600/5205554600). */
    @Test fun noReadIsIssuedAtEndOfFile() {
        assumeTrue(port != null && publicDir != null)
        val size = 5 * 1024 * 1024 // exact multiple of the read size
        val data = seed("stab-eof", 1, size)[0]
        SmbChaosProxy(port!!, swallowReadAt = size.toLong()).use { proxy ->
            val t0 = System.currentTimeMillis()
            val (s, out) = copyThrough(proxy.port, "/public/stab-eof/f0.bin", NetTimeouts(connectMs = 3_000, readMs = 2_000))
            assertEquals(s.toString(), JobState.COMPLETED, s.state)
            assertEquals(0, proxy.eofReads.get())
            assertEquals("retries: ${s.lastError}", 0, s.retries)
            assertTrue(System.currentTimeMillis() - t0 < 5_000)
            assertTrue(File(out, "f0.bin").readBytes().contentEquals(data))
        }
    }

    /** The exact chain from the device log must be classified as reconnectable. */
    @Test fun classifierMatchesFieldChain() {
        val field = com.hierynomus.protocol.transport.TransportException(
            java.util.concurrent.ExecutionException(
                com.hierynomus.smbj.common.SMBRuntimeException(java.util.concurrent.TimeoutException("Timeout expired")),
            ),
        )
        assertTrue(SmbFs.isConnectionError(field))
        assertTrue(SmbFs.isConnectionError(FsException("读取: 连接超时", field)))
        assertTrue(SmbFs.isConnectionError(com.hierynomus.smbj.common.SMBRuntimeException(java.net.SocketException("Connection reset"))))
        assertTrue(SmbFs.isConnectionError(com.hierynomus.smbj.common.SMBRuntimeException("Not enough credits (16 available) to hold onto 16")))
        val status = com.hierynomus.mssmb2.SMBApiException(
            com.hierynomus.mserref.NtStatus.STATUS_ACCESS_DENIED.value, com.hierynomus.mssmb2.SMB2MessageCommandCode.SMB2_READ, "denied", null,
        )
        assertTrue(!SmbFs.isConnectionError(status))
        assertTrue(ErrorText.describe(field).contains("超时"))
    }

    /**
     * Idle connection silently dies (NAT / router / server idle drop, no RST). The SMB2 ECHO
     * keepalive notices it while idle, so the next listing reconnects at once instead of first
     * waiting out a request timeout.
     */
    @Test fun idleEchoDetectsSilentlyDeadConnection() {
        assumeTrue(port != null && publicDir != null)
        SmbChaosProxy(port!!).use { proxy ->
            val t = NetTimeouts(connectMs = 3_000, readMs = 1_500) // echo every 5 s, request timeout 4.5 s
            smb(t, proxy.port).use { fs ->
                fs.list("/public")
                proxy.blackHoleExisting()
                Thread.sleep(5_000 * 2 + t.smbReadStallMs + 500)
                val t0 = System.currentTimeMillis()
                fs.list("/public")
                val took = System.currentTimeMillis() - t0
                assertTrue("list after idle drop took $took ms", took < 2_000)
                assertTrue(proxy.connections.get() >= 2)
            }
        }
    }
}
