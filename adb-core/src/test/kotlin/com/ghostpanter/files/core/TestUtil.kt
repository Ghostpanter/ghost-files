package com.ghostpanter.files.core

import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

fun tempDir(prefix: String = "gf"): File = Files.createTempDirectory(prefix).toFile().also { it.deleteOnExit() }

fun File.writeRandom(size: Int, seed: Int = 1): ByteArray {
    val data = Random(seed).nextBytes(size)
    parentFile.mkdirs()
    writeBytes(data)
    return data
}


/** Breaks the first [failOpens] reads after [failAfter] bytes; records the offsets each open used. */
class FlakyFs(
    private val inner: VirtualFs,
    override val id: String,
    private val failOpens: Int,
    private val failAfter: Long,
) : VirtualFs by inner {
    val readOffsets = CopyOnWriteArrayList<Long>()
    private val opens = AtomicInteger(0)
    override fun openRead(path: String, offset: Long): InputStream {
        readOffsets.add(offset)
        val n = opens.incrementAndGet()
        val s = inner.openRead(path, offset)
        if (n > failOpens) return s
        return object : FilterInputStream(s) {
            var count = 0L
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (count >= failAfter) throw IOException("simulated network drop")
                val r = super.read(b, off, minOf(len, (failAfter - count).toInt().coerceAtLeast(1)))
                if (r > 0) count += r
                return r
            }
        }
    }
}

/** First open blocks forever (like a dead socket) until the stream is closed. */
class HangingFs(private val inner: VirtualFs, override val id: String) : VirtualFs by inner {
    val opens = AtomicInteger(0)
    override fun openRead(path: String, offset: Long): InputStream {
        if (opens.incrementAndGet() > 1) return inner.openRead(path, offset)
        val closed = CountDownLatch(1)
        return object : InputStream() {
            override fun read(): Int {
                closed.await(60, TimeUnit.SECONDS)
                throw IOException("stream closed by watchdog")
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int = read()
            override fun close() { closed.countDown() }
        }
    }
}

/** Slow reader so tests can pause/cancel mid-file. */
class SlowFs(private val inner: VirtualFs, override val id: String, private val delayMs: Long = 20) : VirtualFs by inner {
    override fun openRead(path: String, offset: Long): InputStream = object : FilterInputStream(inner.openRead(path, offset)) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(delayMs)
            return super.read(b, off, minOf(len, 8192))
        }
    }
}

@Suppress("unused")
private fun OutputStream.noop() = Unit
