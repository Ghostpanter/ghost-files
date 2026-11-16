package com.ghostpanter.adb.fastboot

import com.ghostpanter.adb.usb.UsbBulkIo
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Protocol or device error (`FAIL` payload, short packet, unexpected status). */
class FastbootException(message: String) : IOException(message)

/**
 * USB bulk fastboot host (bootloader / fastbootd). Commands and responses follow the
 * public AOSP protocol: ASCII command, then 64-byte `INFO` / `OKAY` / `FAIL` / `DATA` packets.
 *
 * This is a host implementation only. It never attempts an unlock bypass; [ensureWritable]
 * refuses flash / erase / format / set_active unless `getvar:unlocked` is `yes`.
 */
class FastbootClient(private val io: UsbBulkIo) : Closeable {

    data class Packet(val status: Status, val message: String)
    enum class Status { OKAY, FAIL, DATA, INFO }

    data class Result(val status: Status, val message: String, val info: List<String>) {
        val ok: Boolean get() = status == Status.OKAY
        fun requireOk(op: String): Result {
            if (status == Status.FAIL) throw FastbootException("$op: ${message.ifBlank { "FAIL" }}")
            if (status != Status.OKAY) throw FastbootException("$op: unexpected $status $message")
            return this
        }
    }

    data class Info(
        val product: String?,
        val serial: String?,
        val unlocked: Boolean?,
        val isUserspace: Boolean,
        val currentSlot: String?,
        val slotCount: Int,
        val maxDownload: Long,
        val version: String?,
        val bootloader: String?,
        val hardware: String?,
        val vars: Map<String, String>,
    ) {
        val mode: String get() = if (isUserspace) "fastbootd" else "bootloader"
        fun displayName(): String = product?.takeIf { it.isNotBlank() } ?: serial?.takeIf { it.isNotBlank() } ?: "Fastboot"
    }

    enum class RebootTarget(val command: String) {
        SYSTEM("reboot"),
        BOOTLOADER("reboot-bootloader"),
        RECOVERY("reboot-recovery"),
        FASTBOOTD("reboot-fastboot"),
        CONTINUE("continue"),
    }

    @Volatile private var closed = false

    fun command(cmd: String): Result {
        send(cmd)
        return readUntilDone()
    }

    fun getVar(name: String): String? {
        val r = command("getvar:$name")
        return when (r.status) {
            Status.OKAY -> r.message.ifBlank { r.info.lastOrNull().orEmpty() }.ifBlank { "" }
            Status.FAIL -> null
            else -> throw FastbootException("getvar:$name: unexpected ${r.status}")
        }
    }

    fun getVarAll(): Map<String, String> {
        val r = command("getvar:all")
        if (r.status == Status.FAIL) throw FastbootException("getvar:all: ${r.message.ifBlank { "FAIL" }}")
        if (r.status != Status.OKAY) throw FastbootException("getvar:all: unexpected ${r.status}")
        return parseVars(r.info + r.message)
    }

    fun info(): Info {
        // Prefer a single getvar:all; fall back to a short list of well-known names.
        val vars = runCatching { getVarAll() }.getOrElse {
            val keys = listOf(
                "product", "serialno", "unlocked", "secure", "current-slot", "slot-count",
                "is-userspace", "max-download-size", "version", "version-bootloader",
                "hw-revision", "variant", "hardware",
            )
            buildMap {
                for (k in keys) getVar(k)?.let { put(k, it) }
            }
        }
        val unlocked = parseUnlocked(vars["unlocked"], vars["secure"])
        return Info(
            product = vars["product"]?.ifBlank { null },
            serial = vars["serialno"]?.ifBlank { null },
            unlocked = unlocked,
            isUserspace = isYes(vars["is-userspace"]),
            currentSlot = vars["current-slot"]?.trim()?.trimStart('_')?.ifBlank { null },
            slotCount = vars["slot-count"]?.toIntOrNull() ?: 0,
            maxDownload = parseSize(vars["max-download-size"]),
            version = vars["version"]?.ifBlank { null },
            bootloader = vars["version-bootloader"]?.ifBlank { null },
            hardware = (vars["hardware"] ?: vars["variant"] ?: vars["hw-revision"])?.ifBlank { null },
            vars = vars,
        )
    }

    fun reboot(target: RebootTarget): Result = command(target.command).also {
        // Device often drops off the bus immediately after OKAY (or even before).
        if (it.status == Status.FAIL) throw FastbootException("${target.command}: ${it.message.ifBlank { "FAIL" }}")
    }

    fun setActive(slot: String): Result {
        val s = slot.trim().trimStart('_').lowercase()
        if (s != "a" && s != "b") throw FastbootException("invalid slot: $slot")
        ensureWritable("set_active")
        return command("set_active:$s").requireOk("set_active:$s")
    }

    fun erase(partition: String): Result {
        val p = requirePartition(partition)
        ensureWritable("erase:$p")
        return command("erase:$p").requireOk("erase:$p")
    }

    fun format(partition: String, fsType: String? = null): Result {
        val p = requirePartition(partition)
        ensureWritable("format:$p")
        val cmd = if (fsType.isNullOrBlank()) "format:$p" else "format:$p:${fsType.trim()}"
        return command(cmd).requireOk(cmd)
    }

    /**
     * Download [size] bytes from [input], then `flash:<partition>`.
     * Android sparse images larger than [maxDownload] are split into legal sparse parts
     * and flashed in order (same partition), matching the AOSP host.
     */
    fun flash(
        partition: String,
        file: File,
        tmpDir: File? = null,
        progress: (done: Long, total: Long) -> Unit = { _, _ -> },
    ): Result {
        val p = requirePartition(partition)
        ensureWritable("flash:$p")
        if (!file.isFile) throw FastbootException("image not found: ${file.path}")
        val max = effectiveMaxDownload()
        return if (AndroidSparse.isSparse(file) && file.length() > max) {
            AndroidSparse.flashSplit(this, p, file, max, tmpDir ?: file.parentFile, progress)
        } else {
            if (file.length() > max) {
                throw FastbootException(
                    "image is ${file.length()} bytes, larger than max-download-size $max; use a sparse image",
                )
            }
            file.inputStream().use { download(it, file.length(), progress) }
            command("flash:$p").requireOk("flash:$p")
        }
    }

    fun download(input: InputStream, size: Long, progress: (Long, Long) -> Unit = { _, _ -> }) {
        if (size < 0 || size > 0xffff_ffffL) throw FastbootException("download size out of range: $size")
        send("download:%08x".format(size))
        val data = readUntilDone()
        if (data.status != Status.DATA) {
            if (data.status == Status.FAIL) throw FastbootException("download: ${data.message.ifBlank { "FAIL" }}")
            throw FastbootException("download: expected DATA, got ${data.status} ${data.message}")
        }
        val announced = parseHex8(data.message) ?: throw FastbootException("bad DATA size: ${data.message}")
        if (announced.toLong() != size) throw FastbootException("DATA size $announced != $size")
        var sent = 0L
        val buf = ByteArray(DOWNLOAD_CHUNK)
        while (sent < size) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), size - sent).toInt())
            if (n < 0) throw FastbootException("image ended after $sent of $size bytes")
            writeFully(buf, 0, n)
            sent += n
            progress(sent, size)
        }
        readUntilDone().requireOk("download")
    }

    /** Refuses destructive ops unless the device reports `unlocked:yes`. */
    fun ensureWritable(op: String) {
        val v = getVar("unlocked")
        val unlocked = parseUnlocked(v, null)
        if (unlocked != true) {
            throw FastbootException(
                "device is locked (unlocked=${v ?: "unknown"}); $op refused",
            )
        }
    }

    fun effectiveMaxDownload(): Long {
        val n = parseSize(getVar("max-download-size"))
        return if (n > 0) n else DEFAULT_MAX_DOWNLOAD
    }

    private fun send(cmd: String) {
        if (closed) throw FastbootException("fastboot closed")
        if (cmd.isEmpty()) throw FastbootException("empty command")
        val bytes = cmd.toByteArray(StandardCharsets.US_ASCII)
        if (bytes.size > COMMAND_MAX) throw FastbootException("command longer than $COMMAND_MAX bytes")
        writeFully(bytes, 0, bytes.size)
    }

    private fun readUntilDone(): Result {
        val infos = mutableListOf<String>()
        while (true) {
            val p = readPacket()
            when (p.status) {
                Status.INFO -> infos += p.message
                Status.OKAY, Status.FAIL, Status.DATA -> return Result(p.status, p.message, infos)
            }
        }
    }

    private fun readPacket(): Packet {
        val buf = ByteArray(RESPONSE_SZ)
        var zeros = 0
        var n: Int
        while (true) {
            if (closed) throw FastbootException("fastboot closed")
            n = io.bulkIn(buf, 0, buf.size)
            if (n > 0) break
            if (n == 0) {
                if (++zeros > MAX_ZLP) throw FastbootException("endless zero-length packets")
                continue
            }
            throw FastbootException("USB bulk read failed (device detached?)")
        }
        if (n < 4) throw FastbootException("short fastboot response ($n bytes)")
        val token = String(buf, 0, 4, StandardCharsets.US_ASCII)
        val msg = String(buf, 4, n - 4, StandardCharsets.US_ASCII).trimEnd('\u0000').trim()
        val status = when (token) {
            "OKAY" -> Status.OKAY
            "FAIL" -> Status.FAIL
            "DATA" -> Status.DATA
            "INFO" -> Status.INFO
            else -> throw FastbootException("unknown response: $token $msg")
        }
        return Packet(status, msg)
    }

    private fun writeFully(b: ByteArray, off: Int, len: Int) {
        var o = off
        val end = off + len
        while (o < end) {
            if (closed) throw FastbootException("fastboot closed")
            val w = io.bulkOut(b, o, end - o)
            if (w <= 0) throw FastbootException("USB bulk write failed (device detached?)")
            o += w
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { io.close() }
    }

    companion object {
        const val COMMAND_MAX = 64
        const val RESPONSE_SZ = 64
        const val DOWNLOAD_CHUNK = 1024 * 1024
        const val DEFAULT_MAX_DOWNLOAD = 512L * 1024 * 1024
        private const val MAX_ZLP = 64

        val COMMON_PARTITIONS = listOf(
            "boot", "init_boot", "vendor_boot", "dtbo",
            "vbmeta", "vbmeta_system", "vbmeta_vendor",
            "recovery", "system", "vendor", "product", "system_ext", "odm",
            "super", "userdata", "metadata", "cache", "misc",
            "boot_a", "boot_b", "init_boot_a", "init_boot_b",
            "vendor_boot_a", "vendor_boot_b",
        )

        fun parseVars(lines: Iterable<String>): Map<String, String> {
            val out = linkedMapOf<String, String>()
            for (raw in lines) {
                val s = raw.trim()
                if (s.isEmpty()) continue
                val i = s.indexOf(':')
                if (i <= 0) continue
                out[s.substring(0, i).trim()] = s.substring(i + 1).trim()
            }
            return out
        }

        fun parseSize(s: String?): Long {
            if (s.isNullOrBlank()) return 0
            val t = s.trim()
            return try {
                if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2).toLong(16) else t.toLong()
            } catch (_: NumberFormatException) {
                0
            }
        }

        fun parseHex8(s: String): Int? {
            val hex = s.trim().take(8)
            if (hex.length < 8 || !hex.all { it in "0123456789abcdefABCDEF" }) return null
            return hex.toLong(16).toInt()
        }

        fun parseUnlocked(unlocked: String?, secure: String?): Boolean? {
            val u = unlocked?.trim()?.lowercase()
            when (u) {
                "yes", "1", "true" -> return true
                "no", "0", "false" -> return false
            }
            val sec = secure?.trim()?.lowercase()
            return when (sec) {
                "yes", "1", "true" -> false
                "no", "0", "false" -> true
                else -> null
            }
        }

        fun isYes(v: String?): Boolean = when (v?.trim()?.lowercase()) {
            "yes", "1", "true" -> true
            else -> false
        }

        fun requirePartition(name: String): String {
            val p = name.trim()
            if (p.isEmpty() || p.length > 40 || !p.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
                throw FastbootException("invalid partition name: $name")
            }
            return p
        }
    }
}

/**
 * Android sparse image (`0xed26ff3a`). When the file is larger than max-download-size,
 * the host must re-pack groups of chunks (splitting oversized RAW chunks) into smaller
 * sparse files and `flash:` each one to the same partition.
 */
object AndroidSparse {
    const val MAGIC = -0x12D900C6 /* 0xED26FF3A */
    const val HEADER = 28
    const val CHUNK_HDR = 12
    const val RAW = 0xCAC1
    const val FILL = 0xCAC2
    const val DONT_CARE = 0xCAC3
    const val CRC32 = 0xCAC4

    fun isSparse(file: File): Boolean {
        if (!file.isFile || file.length() < HEADER) return false
        RandomAccessFile(file, "r").use { r ->
            val b = ByteArray(4)
            if (r.read(b) != 4) return false
            val magic = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
            return magic == MAGIC
        }
    }

    fun flashSplit(
        client: FastbootClient,
        partition: String,
        file: File,
        maxDownload: Long,
        tmpDir: File,
        progress: (Long, Long) -> Unit,
    ): FastbootClient.Result {
        if (maxDownload < HEADER + CHUNK_HDR + 1L) {
            throw FastbootException("max-download-size $maxDownload is too small for sparse flash")
        }
        tmpDir.mkdirs()
        val total = file.length()
        var uploaded = 0L
        var last: FastbootClient.Result? = null
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(HEADER)
            if (raf.read(hdr) != HEADER) throw FastbootException("truncated sparse header")
            val h = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            if (h.int != MAGIC) throw FastbootException("not a sparse image")
            val major = h.short.toInt() and 0xffff
            val minor = h.short.toInt() and 0xffff
            val hdrSz = h.short.toInt() and 0xffff
            val chunkHdrSz = h.short.toInt() and 0xffff
            val blkSz = h.int
            val totalBlks = h.int
            val totalChunks = h.int
            val checksum = h.int
            if (major != 1 || hdrSz < HEADER || chunkHdrSz < CHUNK_HDR || blkSz <= 0) {
                throw FastbootException("unsupported sparse header (v$major.$minor hdr=$hdrSz blk=$blkSz)")
            }
            if (hdrSz > HEADER) raf.seek(raf.filePointer + (hdrSz - HEADER))
            data class Part(var blks: Int = 0, val chunks: MutableList<ByteArray> = mutableListOf()) {
                fun bytes(): Int = HEADER + chunks.sumOf { it.size }
            }
            fun flush(part: Part) {
                if (part.chunks.isEmpty()) return
                val out = File(tmpDir, "fastboot-sparse-${System.nanoTime()}.img")
                try {
                    out.outputStream().use { os ->
                        val nh = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
                        nh.putInt(MAGIC).putShort(1).putShort(minor.toShort())
                            .putShort(HEADER.toShort()).putShort(CHUNK_HDR.toShort())
                            .putInt(blkSz).putInt(part.blks).putInt(part.chunks.size).putInt(checksum)
                        os.write(nh.array())
                        for (c in part.chunks) os.write(c)
                    }
                    out.inputStream().use { client.download(it, out.length()) { d, t ->
                        val overall = (uploaded + d).coerceAtMost(total)
                        progress(overall, total)
                    } }
                    last = client.command("flash:$partition").requireOk("flash:$partition")
                    uploaded = (uploaded + out.length()).coerceAtMost(total)
                    progress(uploaded, total)
                } finally {
                    out.delete()
                }
            }
            val part = Part()
            fun addChunk(bytes: ByteArray, blks: Int) {
                val next = part.bytes() + bytes.size
                if (part.chunks.isNotEmpty() && next > maxDownload) {
                    flush(part)
                    part.blks = 0
                    part.chunks.clear()
                }
                if (bytes.size + HEADER > maxDownload) {
                    throw FastbootException("sparse chunk ${bytes.size} exceeds max-download-size $maxDownload")
                }
                part.chunks += bytes
                part.blks += blks
            }
            repeat(totalChunks) {
                val ch = ByteArray(chunkHdrSz)
                if (raf.read(ch) != chunkHdrSz) throw FastbootException("truncated sparse chunk header")
                val c = ByteBuffer.wrap(ch).order(ByteOrder.LITTLE_ENDIAN)
                val type = c.short.toInt() and 0xffff
                c.short
                val chunkBlks = c.int
                val totalSz = c.int
                if (totalSz < chunkHdrSz) throw FastbootException("bad sparse chunk size $totalSz")
                val payload = totalSz - chunkHdrSz
                when (type) {
                    RAW -> {
                        val maxPayload = (maxDownload - HEADER - CHUNK_HDR).toInt().coerceAtLeast(blkSz)
                        val maxBlocks = (maxPayload / blkSz).coerceAtLeast(1)
                        var leftBlks = chunkBlks
                        var leftPay = payload.toLong()
                        if (leftPay != chunkBlks.toLong() * blkSz) {
                            throw FastbootException("RAW chunk payload $payload != $chunkBlks * $blkSz")
                        }
                        while (leftBlks > 0) {
                            val nBlks = minOf(leftBlks, maxBlocks)
                            val nPay = nBlks * blkSz
                            val rec = ByteArray(CHUNK_HDR + nPay)
                            ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
                                .putShort(RAW.toShort()).putShort(0).putInt(nBlks).putInt(CHUNK_HDR + nPay)
                            if (raf.read(rec, CHUNK_HDR, nPay) != nPay) throw FastbootException("truncated RAW chunk")
                            addChunk(rec, nBlks)
                            leftBlks -= nBlks
                            leftPay -= nPay
                        }
                    }
                    FILL, DONT_CARE, CRC32 -> {
                        val rec = ByteArray(CHUNK_HDR + payload)
                        System.arraycopy(ch, 0, rec, 0, CHUNK_HDR)
                        if (payload > 0 && raf.read(rec, CHUNK_HDR, payload) != payload) {
                            throw FastbootException("truncated sparse chunk")
                        }
                        // Rewrite header to our 12-byte layout in case the file used a larger chunk header.
                        ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
                            .putShort(type.toShort()).putShort(0).putInt(chunkBlks).putInt(CHUNK_HDR + payload)
                        addChunk(rec, chunkBlks)
                    }
                    else -> throw FastbootException("unknown sparse chunk type 0x${type.toString(16)}")
                }
            }
            flush(part)
        }
        progress(total, total)
        return last ?: throw FastbootException("empty sparse image")
    }
}
