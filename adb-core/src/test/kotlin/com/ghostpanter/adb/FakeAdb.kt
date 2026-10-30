package com.ghostpanter.adb

import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import kotlin.concurrent.thread

/**
 * Local stand-in for adbd: `shell:<cmd>` runs `sh -c <cmd>` with merged stderr (like v1 raw
 * shell), `sync:` is served in-process from the local file system. Paths are local paths.
 */
class FakeAdb : AdbTransport {
    var opened = 0
    val destinations = mutableListOf<String>()
    @Volatile var broken = false
    override val alive: Boolean get() = !broken

    override fun open(destination: String): AdbChannel {
        if (broken) throw IOException("fake transport closed")
        opened++
        synchronized(destinations) { destinations += destination }
        return when {
            destination.startsWith("shell:") -> {
                val p = ProcessBuilder("sh", "-c", destination.removePrefix("shell:")).redirectErrorStream(true).start()
                object : AdbChannel {
                    override val input: InputStream = p.inputStream
                    override val output: OutputStream = p.outputStream
                    override fun close() { p.destroy() }
                }
            }
            destination == "sync:" -> SyncServer().channel
            else -> throw IOException("unsupported service $destination")
        }
    }

    override fun close() { broken = true }

    private class SyncServer {
        private val toServer = PipedInputStream(1 shl 20)
        private val clientOut = PipedOutputStream(toServer)
        private val fromServer = PipedInputStream(1 shl 20)
        private val serverOut = PipedOutputStream(fromServer)
        val channel = object : AdbChannel {
            override val input: InputStream = fromServer
            override val output: OutputStream = clientOut
            override fun close() { runCatching { clientOut.close() }; runCatching { fromServer.close() } }
        }

        init { thread(isDaemon = true, name = "fake-sync") { runCatching { serve() } } }

        private fun le(b: ByteArray, o: Int) = SyncWire.readLe32(b, o)

        private fun serve() {
            val inp = DataInputStream(toServer)
            val h = ByteArray(8)
            while (true) {
                inp.readFully(h)
                val tag = String(h, 0, 4, Charsets.US_ASCII); val n = le(h, 4)
                val arg = ByteArray(n).also { inp.readFully(it) }.toString(Charsets.UTF_8)
                when (tag) {
                    "RECV" -> {
                        val f = File(arg)
                        if (!f.isFile) { fail("No such file or directory"); continue }
                        f.inputStream().use { s ->
                            val buf = ByteArray(SyncWire.CHUNK)
                            while (true) {
                                val r = s.read(buf); if (r < 0) break
                                serverOut.write(SyncWire.header("DATA", r)); serverOut.write(buf, 0, r)
                            }
                        }
                        serverOut.write(SyncWire.header("DONE", 0)); serverOut.flush()
                    }
                    "SEND" -> {
                        val path = arg.substringBeforeLast(',')
                        val f = File(path)
                        val ok = f.parentFile?.isDirectory == true
                        val out = if (ok) f.outputStream() else null
                        while (true) {
                            inp.readFully(h)
                            val t = String(h, 0, 4, Charsets.US_ASCII); val k = le(h, 4)
                            if (t == "DATA") { val d = ByteArray(k); inp.readFully(d); out?.write(d) }
                            else if (t == "DONE") break
                            else throw IOException("bad SEND frame $t")
                        }
                        out?.close()
                        if (ok) { serverOut.write(SyncWire.header("OKAY", 0)); serverOut.flush() } else fail("Permission denied")
                    }
                    "STAT" -> {
                        val f = File(arg); val r = ByteArray(16)
                        "STAT".toByteArray().copyInto(r)
                        if (f.exists()) {
                            SyncWire.le32(r, 4, if (f.isDirectory) 0x41ed else 0x81a4)
                            SyncWire.le32(r, 8, f.length().toInt()); SyncWire.le32(r, 12, (f.lastModified() / 1000).toInt())
                        }
                        serverOut.write(r); serverOut.flush()
                    }
                    "LIST" -> {
                        val dir = File(arg)
                        if (!dir.isDirectory) { fail("Not a directory"); continue }
                        val children = dir.listFiles() ?: emptyArray()
                        for (f in children) {
                            val name = f.name.toByteArray(Charsets.UTF_8)
                            val dent = ByteArray(20)
                            "DENT".toByteArray().copyInto(dent)
                            SyncWire.le32(dent, 4, if (f.isDirectory) 0x41ed else 0x81a4)
                            SyncWire.le32(dent, 8, f.length().toInt())
                            SyncWire.le32(dent, 12, (f.lastModified() / 1000).toInt())
                            SyncWire.le32(dent, 16, name.size)
                            serverOut.write(dent)
                            serverOut.write(name)
                        }
                        // Empty-name DENT ends the listing (AOSP adbd convention).
                        val end = ByteArray(20)
                        "DENT".toByteArray().copyInto(end)
                        serverOut.write(end); serverOut.flush()
                    }
                    else -> return
                }
            }
        }

        private fun fail(msg: String) {
            val m = msg.toByteArray()
            serverOut.write(SyncWire.header("FAIL", m.size)); serverOut.write(m); serverOut.flush()
        }
    }
}
