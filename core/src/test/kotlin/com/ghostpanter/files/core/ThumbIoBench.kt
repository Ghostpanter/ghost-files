package com.ghostpanter.files.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Manual I/O benchmark (skipped unless GF_BENCH_DIR is set): what a video thumbnail costs over a
 * Root-like merged-stderr shell backend — old "copy whole file" vs new random-access block reads
 * (header + moov + first ~6 MB of mdat, in the 256 KB blocks FsMediaDataSource uses).
 */
class ThumbIoBench {
    @Test fun bench() {
        val dir = System.getenv("GF_BENCH_DIR") ?: ""
        assumeTrue(dir.isNotEmpty())
        val runner = ShellRunner { s -> ProcessBuilder("sh", "-c", s).redirectErrorStream(true).start() }
        val fs = ShellFs("root", "Root", runner, persistent = true)
        val block = 256 * 1024
        for (f in File(dir).listFiles()!!.filter { it.name.endsWith(".mp4") }.sortedBy { it.length() }) {
            val len = f.length()
            // Locate top-level boxes the way a parser would (8/16-byte headers), then touch moov + early mdat.
            val blocks = sortedSetOf<Long>()
            var pos = 0L; val h = ByteArray(16)
            var mdat = 0L; var moov = 0L; var moovLen = 0L
            while (pos + 8 <= len) {
                fs.readAt(f.path, pos, h, 0, 16); blocks += pos / block
                var n = ((h[0].toLong() and 0xff) shl 24) or ((h[1].toLong() and 0xff) shl 16) or ((h[2].toLong() and 0xff) shl 8) or (h[3].toLong() and 0xff)
                if (n == 1L) n = (0 until 8).fold(0L) { a, i -> (a shl 8) or (h[8 + i].toLong() and 0xff) }
                val t = String(h, 4, 4)
                if (t == "mdat") mdat = pos; if (t == "moov") { moov = pos; moovLen = n }
                pos += n
            }
            val t0 = System.nanoTime()
            val buf = ByteArray(block)
            val want = sortedSetOf<Long>(0L)
            for (b in moov / block..(moov + moovLen - 1) / block) want += b
            for (b in mdat / block..(mdat + (6L shl 20)) / block) want += b
            var bytes = 0L
            for (b in want) { val n = fs.readAt(f.path, b * block, buf, 0, minOf(block.toLong(), len - b * block).toInt()); bytes += maxOf(n, 0) }
            val newMs = (System.nanoTime() - t0) / 1_000_000
            val t1 = System.nanoTime()
            val tmp = File.createTempFile("thumb", ".mp4")
            fs.openRead(f.path).use { i -> tmp.outputStream().use { o -> i.copyTo(o, 64 * 1024) } }
            val oldMs = (System.nanoTime() - t1) / 1_000_000
            tmp.delete()
            println("BENCH ${f.name} size=${len shr 20}MB moovAt=${moov shr 20}MB new=${newMs}ms (${want.size} blocks, ${bytes shr 10}KB) old_full_copy=${oldMs}ms")
        }
        fs.close()
    }
}
