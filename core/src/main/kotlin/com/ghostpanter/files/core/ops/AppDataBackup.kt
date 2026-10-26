package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

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
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) throw FsException(tr("非法包名", "Invalid package name"))
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
            throw FsException(tr("备份失败", "Backup failed") + ": ${r.stderr.trim().ifEmpty { r.stdout.trim().ifEmpty { "exit ${r.code}" } }}")
        }
        shell.openRead(tmp).use { ins -> destTar.outputStream().use { ins.copyTo(it, 256 * 1024) } }
        runCatching { shell.delete(tmp, recursive = false) }
        return destTar
    }

    /**
     * Restores [srcTar] into /data/data/<pkg> for an installed app with [uid]: clears the current data,
     * copies the backup in, fixes owner (uid / cache gid) and SELinux labels. See [AppBackupFiles.restoreScript].
     */
    fun restore(shell: ShellFs, packageName: String, srcTar: File, uid: Int, timeoutSec: Long = 600) {
        if (!AppBackupFiles.validPackage(packageName)) throw FsException(tr("非法包名", "Invalid package name"))
        if (!srcTar.isFile) throw FsException(tr("备份文件不存在", "Backup file not found"))
        val tmp = "/data/local/tmp/gf-restore-$packageName.tar"
        shell.openWrite(tmp).use { out -> srcTar.inputStream().use { it.copyTo(out, 256 * 1024) } }
        val r = shell.exec(AppBackupFiles.restoreScript(packageName, uid, tmp), timeoutSec)
        if (!r.ok || !r.stdout.contains("OK")) {
            runCatching { shell.exec("rm -rf /data/local/tmp/gf-restore-$packageName $tmp", 30) }
            val why = when (AppBackupFiles.explainRestore(r.stderr)) {
                "NOT_INSTALLED" -> tr("应用未安装", "App is not installed")
                "BAD_TAR" -> tr("备份内容与包名不符", "Backup does not match this package")
                else -> r.stderr.trim().ifEmpty { r.stdout.trim().ifEmpty { "exit ${r.code}" } }
            }
            throw FsException(tr("恢复失败", "Restore failed") + ": " + why)
        }
    }

    fun extractApkPaths(shell: ShellFs, packageName: String): List<String> {
        if (!packageName.matches(Regex("[A-Za-z0-9._]+"))) throw FsException(tr("非法包名", "Invalid package name"))
        val r = shell.exec("pm path ${VPath.shellQuote(packageName)}")
        if (!r.ok) throw FsException(tr("pm path 失败", "pm path failed") + ": ${r.stderr.trim()}")
        return r.stdout.lines().mapNotNull { line ->
            val p = line.removePrefix("package:").trim()
            p.ifEmpty { null }
        }
    }
}
