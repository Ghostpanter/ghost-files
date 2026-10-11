package com.ghostpanter.files.core

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * SMB-aware TCP proxy (Direct TCP framing: 4-byte length + SMB2 message) for fault injection
 * without kernel netem:
 *  - [blackHoleAfter]: once that many response bytes went through on connection #0, every later
 *    frame on that connection is silently swallowed (socket stays open, like a NAT/router or a
 *    hung server - NOT a reset). New connections are healthy.
 *  - [stallReadsMs] / [stallAfter]: READ responses of all connections are held back for a while
 *    (emulates a USB disk on a router stalling for seconds).
 *  - [swallowReadAt]: READ requests at offset >= this value never reach the server (emulates a
 *    server that never answers a read at EOF) and are counted in [eofReads].
 */
class SmbChaosProxy(
    private val target: Int,
    private val blackHoleAfter: Long = Long.MAX_VALUE,
    private val stallAfter: Long = Long.MAX_VALUE,
    private val stallReadsMs: Long = 0,
    private val swallowReadAt: Long = Long.MAX_VALUE,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort
    private val socks = CopyOnWriteArrayList<Socket>()
    val connections = AtomicInteger()
    val eofReads = AtomicInteger()
    val blackHoled = AtomicInteger()
    private val down = AtomicLong()
    @Volatile private var stallUntil = 0L
    @Volatile private var stalled = false
    /** Connections with index < this are black-holed from now on (see [blackHoleExisting]). */
    @Volatile private var holeBelow = 0

    /** Silently stop forwarding on every connection opened so far (new ones stay healthy). */
    fun blackHoleExisting() { holeBelow = connections.get() }

    init {
        Thread {
            while (!server.isClosed) {
                val c = runCatching { server.accept() }.getOrNull() ?: break
                val s = Socket("127.0.0.1", target)
                socks += c; socks += s
                val idx = connections.getAndIncrement()
                pump(c.getInputStream(), s.getOutputStream(), idx, toClient = false) { c.close(); s.close() }
                pump(s.getInputStream(), c.getOutputStream(), idx, toClient = true) { c.close(); s.close() }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun pump(i: InputStream, o: OutputStream, conn: Int, toClient: Boolean, done: () -> Unit) = Thread {
        val din = DataInputStream(i)
        var connDown = 0L
        var holed = false
        runCatching {
            while (true) {
                val hdr = ByteArray(4); din.readFully(hdr)
                val len = ((hdr[1].toInt() and 0xff) shl 16) or ((hdr[2].toInt() and 0xff) shl 8) or (hdr[3].toInt() and 0xff)
                val body = ByteArray(len); din.readFully(body)
                val isSmb2 = len >= 64 && body[0] == 0xFE.toByte() && body[1] == 'S'.code.toByte()
                val cmd = if (isSmb2) ByteBuffer.wrap(body, 12, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() else -1
                if (toClient) {
                    connDown += len + 4
                    val total = down.addAndGet(len + 4L)
                    if ((conn == 0 && connDown > blackHoleAfter) || conn < holeBelow) { if (!holed) blackHoled.incrementAndGet(); holed = true }
                    if (holed) continue // swallow silently, keep the socket open
                    if (cmd == 8 && stallReadsMs > 0 && total > stallAfter && !stalled) {
                        stalled = true; stallUntil = System.currentTimeMillis() + stallReadsMs
                    }
                    if (cmd == 8) { val w = stallUntil - System.currentTimeMillis(); if (w > 0) Thread.sleep(w) }
                } else if (conn < holeBelow) {
                    continue
                } else if (cmd == 8 && len >= 64 + 16) {
                    val off = ByteBuffer.wrap(body, 64 + 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
                    if (off >= swallowReadAt) { eofReads.incrementAndGet(); continue }
                }
                o.write(hdr); o.write(body); o.flush()
            }
        }
        runCatching { done() }
    }.apply { isDaemon = true }.start()

    override fun close() {
        runCatching { server.close() }
        for (x in socks) runCatching { x.close() }
    }
}
