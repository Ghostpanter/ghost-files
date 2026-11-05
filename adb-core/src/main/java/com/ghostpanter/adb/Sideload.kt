package com.ghostpanter.adb

import com.ghostpanter.files.core.tr
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Recovery `adb sideload` for OTA / flash zips.
 *
 * Prefer the modern host protocol (`sideload_host:` / `sideload-host:`): recovery requests
 * 8-digit decimal block numbers (or `DONEDONE`); we reply with raw block bytes (last block
 * unpadded). Fall back to streaming the whole file over `sideload:` for older recoveries.
 *
 * This is ADB-in-recovery, not fastboot. USB filter stays on the ADB interface only.
 */
object Sideload {
    /** AOSP default chunk (SIDELOAD_HOST_BLOCK_SIZE). */
    const val BLOCK_SIZE = 64 * 1024
    const val OPEN_TIMEOUT_MS = 60_000L

    data class Result(
        val mode: String,
        /** Bytes written on the wire (host protocol may re-serve blocks ≈2×). */
        val bytesServed: Long,
        val fileSize: Long,
    )

    /**
     * Push [zip] to a device that is already in recovery sideload mode.
     * [progress] receives (bytesServedSoFar, fileSize).
     */
    fun push(dev: AdbDevice, zip: File, progress: (Long, Long) -> Unit = { _, _ -> }): Result {
        if (!zip.isFile) throw IOException(tr("找不到刷机包：", "Package not found: ") + zip.path)
        val size = zip.length()
        if (size <= 0L) throw IOException(tr("刷机包为空", "Package is empty"))
        if (size > Int.MAX_VALUE.toLong()) throw IOException(tr("刷机包过大（超过 2 GiB）", "Package too large (>2 GiB)"))

        val hosts = listOf(
            "sideload_host:$size:$BLOCK_SIZE",
            "sideload-host:$size:$BLOCK_SIZE",
        )
        var lastOpen: IOException? = null
        for (dest in hosts) {
            val ch = try {
                open(dev, dest)
            } catch (e: IOException) {
                lastOpen = e
                continue
            }
            return ch.use { serveHost(it, zip, size, progress) }.also {
                progress(it.bytesServed.coerceAtLeast(size), size)
            }
        }
        // Legacy: stream the whole zip once.
        return try {
            open(dev, "sideload:").use { ch -> streamLegacy(ch, zip, size, progress) }
        } catch (e: IOException) {
            throw IOException(
                tr(
                    "无法打开 sideload 服务（请确认对方已在 Recovery 选择「Apply update from ADB / 从 ADB 应用更新」并等待）。",
                    "Cannot open sideload (confirm the peer is in Recovery → Apply update from ADB and waiting).",
                ) + " " + (lastOpen?.message ?: e.message).orEmpty(),
                e,
            )
        }
    }

    private fun open(dev: AdbDevice, dest: String): AdbChannel = try {
        dev.open(dest, OPEN_TIMEOUT_MS)
    } catch (e: IOException) {
        AdbPool.invalidate(dev.serial)
        dev.open(dest, OPEN_TIMEOUT_MS)
    }

    private fun serveHost(ch: AdbChannel, zip: File, size: Long, progress: (Long, Long) -> Unit): Result {
        var served = 0L
        val cmd = ByteArray(8)
        val buf = ByteArray(BLOCK_SIZE)
        RandomAccessFile(zip, "r").use { raf ->
            while (true) {
                SyncWire.readFully(ch.input, cmd)
                val token = String(cmd, Charsets.US_ASCII)
                if (token == "DONEDONE") break
                val block = token.toIntOrNull()
                    ?: throw IOException(tr("sideload 协议异常：", "Bad sideload command: ") + token)
                val offset = block.toLong() * BLOCK_SIZE
                if (offset < 0L || offset >= size) {
                    throw IOException(tr("sideload 请求越界：块 ", "Sideload block out of range: ") + block)
                }
                val toWrite = minOf(BLOCK_SIZE.toLong(), size - offset).toInt()
                raf.seek(offset)
                var got = 0
                while (got < toWrite) {
                    val r = raf.read(buf, got, toWrite - got)
                    if (r < 0) throw IOException(tr("读取刷机包失败", "Failed reading package"))
                    got += r
                }
                ch.output.write(buf, 0, toWrite)
                ch.output.flush()
                served += toWrite
                progress(served, size)
            }
        }
        // Drain any trailing status the recovery may send.
        runCatching {
            val rest = ByteArray(256)
            val n = ch.input.read(rest)
            if (n > 0) {
                val s = String(rest, 0, n, Charsets.UTF_8).trim()
                if (s.contains("fail", true) || s.contains("error", true)) {
                    throw IOException(tr("对方 Recovery 报告失败：", "Recovery reported failure: ") + s)
                }
            }
        }
        return Result(mode = "sideload_host", bytesServed = served, fileSize = size)
    }

    private fun streamLegacy(ch: AdbChannel, zip: File, size: Long, progress: (Long, Long) -> Unit): Result {
        var served = 0L
        val buf = ByteArray(BLOCK_SIZE)
        zip.inputStream().use { inp ->
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                ch.output.write(buf, 0, n)
                ch.output.flush()
                served += n
                progress(served, size)
            }
        }
        runCatching { ch.output.close() }
        val status = ByteArrayOutputLimited(ch.input, 4096)
        if (status.contains("fail", true) || status.contains("error", true)) {
            throw IOException(tr("对方 Recovery 报告失败：", "Recovery reported failure: ") + status.trim())
        }
        return Result(mode = "sideload", bytesServed = served, fileSize = size)
    }

    /** Drain a small status banner without blocking if recovery already hung up. */
    private fun ByteArrayOutputLimited(input: java.io.InputStream, max: Int): String {
        val out = ByteArray(max)
        var n = 0
        while (n < max) {
            val avail = runCatching { input.available() }.getOrDefault(0)
            if (avail <= 0) break
            val r = input.read(out, n, minOf(max - n, avail))
            if (r < 0) break
            n += r
        }
        return if (n <= 0) "" else String(out, 0, n, Charsets.UTF_8)
    }
}
