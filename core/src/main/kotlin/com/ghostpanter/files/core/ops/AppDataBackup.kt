package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.ShellFs
import com.ghostpanter.files.core.VPath
import java.io.File

/**
 * Root-assisted app data backup / restore (tar of /data/data/<pkg>).
 * Requires a working [ShellFs] with root (or elevated shell that can read app data).
 */
object AppDataBackup {
    fun backup(shell: ShellFs, packageName: String, destTar: File, timeoutSec: Long = 600): File {
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) throw FsException("非法包名")
        destTar.parentFile?.mkdirs()
        val tmp = "/data/local/tmp/gf-backup-$packageName.tar"
        val data = "/data/data/$packageName"
        val script = """
            set -e
            if [ ! -d $data ]; then echo NO_DATA >&2; exit 2; fi
            tar -C /data/data -cf $tmp $packageName
            chmod 644 $tmp 2>/dev/null || true
            echo OK
        """.trimIndent()
        val r = shell.exec(script, timeoutSec)
        if (!r.ok || !r.stdout.contains("OK")) {
            throw FsException("备份失败: ${r.stderr.trim().ifEmpty { r.stdout.trim().ifEmpty { "exit ${r.code}" } }}")
        }
        shell.openRead(tmp).use { ins -> destTar.outputStream().use { ins.copyTo(it, 256 * 1024) } }
        runCatching { shell.delete(tmp, recursive = false) }
        return destTar
    }

    fun restore(shell: ShellFs, packageName: String, srcTar: File, timeoutSec: Long = 600) {
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) throw FsException("非法包名")
        if (!srcTar.isFile) throw FsException("备份文件不存在")
        val tmp = "/data/local/tmp/gf-restore-$packageName.tar"
        shell.openWrite(tmp).use { out -> srcTar.inputStream().use { it.copyTo(out, 256 * 1024) } }
        val script = """
            set -e
            tar -C /data/data -xf $tmp
            rm -f $tmp
            echo OK
        """.trimIndent()
        val r = shell.exec(script, timeoutSec)
        if (!r.ok || !r.stdout.contains("OK")) {
            throw FsException("恢复失败: ${r.stderr.trim().ifEmpty { r.stdout.trim().ifEmpty { "exit ${r.code}" } }}")
        }
    }

    fun extractApkPaths(shell: ShellFs, packageName: String): List<String> {
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) throw FsException("非法包名")
        val r = shell.exec("pm path ${VPath.shellQuote(packageName)}")
        if (!r.ok) throw FsException("pm path 失败: ${r.stderr.trim()}")
        return r.stdout.lines().mapNotNull { line ->
            val p = line.removePrefix("package:").trim()
            p.ifEmpty { null }
        }
    }
}
