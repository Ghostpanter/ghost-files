package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.ShellFs
import com.ghostpanter.files.core.VPath
import java.io.File
import java.nio.file.Files

/** Soft-link creation + Root remount of system partitions read-write. */
object SymlinkOps {
    fun createLink(linkPath: String, target: String, shell: ShellFs? = null) {
        val link = VPath.normalize(linkPath)
        val tgt = target
        if (shell != null) {
            val r = shell.exec("ln -s -- ${VPath.shellQuote(tgt)} ${VPath.shellQuote(link)}")
            if (!r.ok) throw FsException("创建软链接失败: ${r.stderr.trim().ifEmpty { "exit ${r.code}" }}")
            return
        }
        val f = File(link)
        if (f.exists() || Files.isSymbolicLink(f.toPath())) throw FsException("已存在: $link")
        f.parentFile?.mkdirs()
        try {
            Files.createSymbolicLink(f.toPath(), File(tgt).toPath())
        } catch (e: Exception) {
            val p = Runtime.getRuntime().exec(arrayOf("ln", "-s", tgt, link))
            val code = p.waitFor()
            if (code != 0) throw FsException("创建软链接失败（本机可能无权限，请用 Root/Shizuku）: ${e.message}", e)
        }
    }

    data class MountInfo(val device: String, val mountPoint: String, val fsType: String, val options: String) {
        val isRo: Boolean get() = options.split(',').any { it == "ro" }
        val isRw: Boolean get() = options.split(',').any { it == "rw" }
    }

    fun parseMounts(procMounts: String): List<MountInfo> =
        procMounts.lineSequence().mapNotNull { line ->
            val p = line.split(Regex("\\s+"))
            if (p.size < 4) null else MountInfo(p[0], p[1], p[2], p[3])
        }.toList()

    fun remountRw(shell: ShellFs, mountPoint: String): String {
        val mp = VPath.normalize(mountPoint)
        val q = VPath.shellQuote(mp)
        // Avoid Kotlin string-template collisions with shell $VAR by using ${'$'}.
        val dollar = "${'$'}"
        val script = """
            set -e
            MP=$q
            if mount -o remount,rw "${dollar}MP" 2>/dev/null; then echo OK_remount; exit 0; fi
            if mount -o rw,remount "${dollar}MP" 2>/dev/null; then echo OK_remount; exit 0; fi
            DEV=${dollar}(awk -v m="${dollar}MP" '${dollar}2==m {print ${dollar}1; exit}' /proc/mounts)
            [ -n "${dollar}DEV" ] || { echo remount_fail >&2; exit 1; }
            mount -o rw,remount "${dollar}DEV" "${dollar}MP"
            echo OK_remount_dev
        """.trimIndent()
        val r = shell.exec(script)
        if (!r.ok) throw FsException("重新挂载读写失败: ${r.stderr.trim().ifEmpty { r.stdout.trim() }}")
        return r.stdout.trim()
    }

    fun remountRo(shell: ShellFs, mountPoint: String): String {
        val mp = VPath.normalize(mountPoint)
        val r = shell.exec("mount -o remount,ro ${VPath.shellQuote(mp)} || mount -o ro,remount ${VPath.shellQuote(mp)}")
        if (!r.ok) throw FsException("重新挂载只读失败: ${r.stderr.trim()}")
        return r.stdout.trim()
    }

    fun readProcMounts(shell: ShellFs? = null): List<MountInfo> {
        val text = if (shell != null) {
            val r = shell.exec("cat /proc/mounts")
            if (!r.ok) throw FsException("读取 /proc/mounts 失败")
            r.stdout
        } else File("/proc/mounts").readText()
        return parseMounts(text)
    }
}
