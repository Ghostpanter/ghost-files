package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.VirtualFs
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/** Streaming MD5 / SHA-1 / SHA-256 / CRC32 in one pass (works on any [VirtualFs], incl. LAN). */
object Checksums {
    enum class Algo(val label: String) { CRC32("CRC32"), MD5("MD5"), SHA1("SHA-1"), SHA256("SHA-256"), SHA512("SHA-512") }

    fun compute(
        input: InputStream,
        algos: Set<Algo> = setOf(Algo.MD5, Algo.SHA1, Algo.SHA256),
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): Map<Algo, String> {
        val digests = algos.filter { it != Algo.CRC32 }.associateWith {
            MessageDigest.getInstance(if (it == Algo.SHA1) "SHA-1" else if (it == Algo.SHA256) "SHA-256" else if (it == Algo.SHA512) "SHA-512" else "MD5")
        }
        val crc = if (Algo.CRC32 in algos) CRC32() else null
        val buf = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
            if (isCancelled()) throw com.ghostpanter.files.core.FsException("已取消")
            val n = input.read(buf)
            if (n < 0) break
            for (d in digests.values) d.update(buf, 0, n)
            crc?.update(buf, 0, n)
            done += n
            onProgress(done)
        }
        val out = LinkedHashMap<Algo, String>()
        for (a in Algo.values()) {
            if (a !in algos) continue
            out[a] = if (a == Algo.CRC32) "%08x".format(crc!!.value) else digests.getValue(a).digest().toHex()
        }
        return out
    }

    fun compute(fs: VirtualFs, path: String, algos: Set<Algo> = setOf(Algo.MD5, Algo.SHA1, Algo.SHA256),
                isCancelled: () -> Boolean = { false }, onProgress: (Long) -> Unit = {}): Map<Algo, String> =
        fs.openRead(path).use { compute(it, algos, isCancelled, onProgress) }

    /** Which algorithm (if any) produced [expected]; case/space-insensitive. */
    fun match(results: Map<Algo, String>, expected: String): Algo? {
        val token = expected.trim().split(Regex("\\s+")).firstOrNull()?.trimStart('*')?.lowercase().orEmpty()
        if (token.isEmpty()) return null
        return results.entries.firstOrNull { it.value.equals(token, true) }?.key
    }

    /** "<hash>  <name>" line, sha256sum / md5sum compatible. */
    fun sumLine(hash: String, name: String) = "$hash  $name\n"

    fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) { val v = b.toInt() and 0xff; sb.append(hex[v ushr 4]).append(hex[v and 0xf]) }
        return sb.toString()
    }
}
