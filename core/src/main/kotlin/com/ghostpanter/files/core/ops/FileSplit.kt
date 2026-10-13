package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import java.util.Locale

/** Split a file into fixed-size parts and merge them back (MT-style .001/.002). */
object FileSplit {
    fun split(
        fs: VirtualFs,
        path: String,
        partSize: Long,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): List<String> {
        require(partSize >= 64 * 1024) { "分卷至少 64KB" }
        val st = fs.stat(path) ?: throw FsException("文件不存在")
        if (st.isDirectory) throw FsException("不能分割目录")
        val parent = VPath.parent(path)
        val base = VPath.name(path)
        val out = ArrayList<String>()
        fs.openRead(path).use { input ->
            val buf = ByteArray(256 * 1024)
            var part = 1
            var writtenInPart = 0L
            var total = 0L
            var current: String? = null
            var outStream: java.io.OutputStream? = null
            fun openPart() {
                val name = "%s.%03d".format(Locale.US, base, part)
                current = VPath.join(parent, name)
                outStream = fs.openWrite(current!!)
                out.add(current!!)
                writtenInPart = 0
                part++
            }
            openPart()
            while (true) {
                if (isCancelled()) { outStream?.close(); throw FsException("已取消") }
                val n = input.read(buf); if (n < 0) break
                var off = 0
                while (off < n) {
                    val room = (partSize - writtenInPart).toInt().coerceAtMost(n - off)
                    outStream!!.write(buf, off, room)
                    writtenInPart += room; total += room; off += room
                    if (writtenInPart >= partSize && off < n) {
                        outStream!!.close(); openPart()
                    }
                }
                onProgress(total)
            }
            outStream?.close()
        }
        return out
    }

    fun merge(
        fs: VirtualFs,
        firstPartPath: String,
        destPath: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (Long) -> Unit = {},
    ): Long {
        val name = VPath.name(firstPartPath)
        val m = Regex("""^(.+)\.(\d{3})$""").matchEntire(name) ?: throw FsException("不是 .001 分卷名")
        val base = m.groupValues[1]
        val parent = VPath.parent(firstPartPath)
        var idx = m.groupValues[2].toInt()
        var total = 0L
        fs.openWrite(destPath).use { out ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val part = VPath.join(parent, "%s.%03d".format(Locale.US, base, idx))
                if (fs.stat(part) == null) break
                fs.openRead(part).use { input ->
                    while (true) {
                        if (isCancelled()) throw FsException("已取消")
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); total += n; onProgress(total)
                    }
                }
                idx++
            }
        }
        if (total == 0L) throw FsException("未找到分卷")
        return total
    }
}
