package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VirtualFs
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile as JZipFile

/** Archive integrity test (CRC) for ZIP / 7z. */
object ArchiveTest {
    data class Result(val ok: Boolean, val checked: Int, val detail: String)

    fun test(
        fs: VirtualFs, path: String, localFile: File?, tmpDir: File,
        password: String? = null, isCancelled: () -> Boolean = { false },
    ): Result {
        val kind = Archives.kindOf(path) ?: throw FsException("不支持测试该格式")
        val (file, temp) = spool(fs, path, localFile, tmpDir)
        try {
            return when (kind) {
                Archives.Kind.ZIP -> testZip(file, password, isCancelled)
                Archives.Kind.SEVEN_Z -> test7z(file, password, isCancelled)
                else -> throw FsException("暂仅支持测试 ZIP / 7z（其它格式请先解压验证）")
            }
        } finally {
            if (temp) file.delete()
        }
    }

    private fun testZip(file: File, password: String?, isCancelled: () -> Boolean): Result {
        if (!password.isNullOrEmpty()) {
            val z = ZipFile(file, password.toCharArray())
            if (!z.isValidZipFile) return Result(false, 0, "ZIP 结构无效")
            var n = 0
            for (h in z.fileHeaders) {
                if (isCancelled()) throw FsException("已取消")
                if (h.isDirectory) continue
                z.getInputStream(h).use { ins ->
                    val buf = ByteArray(64 * 1024)
                    val crc = CRC32()
                    while (true) {
                        val r = ins.read(buf); if (r < 0) break
                        crc.update(buf, 0, r)
                    }
                    if (h.crc.toLong() != 0L && crc.value != h.crc.toLong()) {
                        return Result(false, n, "CRC 不匹配: ${h.fileName}")
                    }
                }
                n++
            }
            return Result(true, n, "ZIP 校验通过（$n 个文件）")
        }
        JZipFile(file).use { z ->
            var n = 0
            val entries = z.entries()
            while (entries.hasMoreElements()) {
                if (isCancelled()) throw FsException("已取消")
                val e = entries.nextElement()
                if (e.isDirectory) continue
                z.getInputStream(e).use { ins ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val r = ins.read(buf); if (r < 0) break }
                }
                n++
            }
            return Result(true, n, "ZIP 可读性通过（$n 个文件）")
        }
    }

    private fun test7z(file: File, password: String?, isCancelled: () -> Boolean): Result {
        val b = SevenZFile.builder().setFile(file)
        if (!password.isNullOrEmpty()) b.setPassword(password.toCharArray())
        var n = 0
        b.get().use { z ->
            while (true) {
                if (isCancelled()) throw FsException("已取消")
                val e = z.nextEntry ?: break
                if (e.isDirectory) continue
                val buf = ByteArray(64 * 1024)
                while (true) { val r = z.read(buf); if (r < 0) break }
                n++
            }
        }
        return Result(true, n, "7z 可读性通过（$n 个文件）")
    }

    private fun spool(fs: VirtualFs, path: String, localFile: File?, tmpDir: File): Pair<File, Boolean> {
        if (localFile != null && localFile.isFile) return localFile to false
        tmpDir.mkdirs()
        val f = File.createTempFile("test", ".arc", tmpDir)
        fs.openRead(path).use { input -> f.outputStream().use { input.copyTo(it) } }
        return f to true
    }
}
