package com.ghostpanter.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class SideloadTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun hostProtocolServesBlocksAndDone() {
        val payload = ByteArray(Sideload.BLOCK_SIZE + 100) { (it % 251).toByte() }
        val zip = tmp.newFile("ota.zip").also { it.writeBytes(payload) }

        val toHost = PipedInputStream(1 shl 20)
        val fromClient = PipedOutputStream(toHost) // recovery → host (commands)
        val fromHost = PipedInputStream(1 shl 20)
        val toClient = PipedOutputStream(fromHost) // host → recovery (data)

        val channel = object : AdbChannel {
            override val input: InputStream = toHost
            override val output: OutputStream = toClient
            override fun close() {
                runCatching { fromClient.close() }
                runCatching { toClient.close() }
            }
        }

        val transport = object : AdbTransport {
            override val alive = true
            override fun open(destination: String): AdbChannel {
                assertTrue(destination.startsWith("sideload_host:"))
                assertTrue(destination.contains(":${payload.size}:"))
                return channel
            }
            override fun close() {}
        }
        val dev = AdbDevice("usb-test:1") { transport }

        val err = AtomicReference<Throwable?>(null)
        val done = CountDownLatch(1)
        thread(isDaemon = true, name = "fake-recovery") {
            try {
                // request block 0, block 1 (partial), then DONEDONE
                fromClient.write("00000000".toByteArray())
                fromClient.flush()
                val b0 = ByteArray(Sideload.BLOCK_SIZE)
                readFully(fromHost, b0)
                assertEquals(payload.copyOfRange(0, Sideload.BLOCK_SIZE).toList(), b0.toList())

                fromClient.write("00000001".toByteArray())
                fromClient.flush()
                val b1 = ByteArray(100)
                readFully(fromHost, b1)
                assertEquals(payload.copyOfRange(Sideload.BLOCK_SIZE, payload.size).toList(), b1.toList())

                fromClient.write("DONEDONE".toByteArray())
                fromClient.flush()
            } catch (t: Throwable) {
                err.set(t)
            } finally {
                done.countDown()
            }
        }

        val result = Sideload.push(dev, zip)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        err.get()?.let { throw it }
        assertEquals("sideload_host", result.mode)
        assertEquals(payload.size.toLong(), result.fileSize)
        assertEquals(payload.size.toLong(), result.bytesServed)
    }

    @Test fun fallsBackWhenHostOpenFails() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val zip = tmp.newFile("old.zip").also { it.writeBytes(payload) }
        var opens = 0
        val toHost = PipedInputStream(4096)
        val fromClient = PipedOutputStream(toHost)
        val fromHost = PipedInputStream(4096)
        val toClient = PipedOutputStream(fromHost)
        val legacy = object : AdbChannel {
            override val input: InputStream = toHost
            override val output: OutputStream = toClient
            override fun close() {
                runCatching { fromClient.close() }
                runCatching { toClient.close() }
            }
        }
        val transport = object : AdbTransport {
            override val alive = true
            override fun open(destination: String): AdbChannel {
                opens++
                return when {
                    destination.startsWith("sideload_host:") || destination.startsWith("sideload-host:") ->
                        throw IOException("unknown service")
                    destination == "sideload:" -> legacy
                    else -> throw IOException(destination)
                }
            }
            override fun close() {}
        }
        val got = AtomicReference<ByteArray?>(null)
        val done = CountDownLatch(1)
        thread(isDaemon = true) {
            try {
                val buf = ByteArray(64)
                var n = 0
                while (n < payload.size) {
                    val r = fromHost.read(buf, n, payload.size - n)
                    if (r < 0) break
                    n += r
                }
                got.set(buf.copyOf(n))
            } finally {
                done.countDown()
            }
        }
        val result = Sideload.push(AdbDevice("usb-x:1") { transport }, zip)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals("sideload", result.mode)
        assertEquals(payload.toList(), got.get()!!.toList())
        assertTrue(opens >= 3)
    }

    private fun readFully(inp: InputStream, b: ByteArray) {
        var n = 0
        while (n < b.size) {
            val r = inp.read(b, n, b.size - n)
            if (r < 0) throw IOException("eof at $n/${b.size}")
            n += r
        }
    }
}
