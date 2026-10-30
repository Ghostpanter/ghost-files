package com.ghostpanter.adb

import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.ShellFs
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import com.ghostpanter.files.core.tr
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * A target device's file system over ADB (backend id `adb:<serial>`, or `adbsu:<serial>` to go
 * through `su 0` on a rooted target). Listing / stat / mkdir / delete / rename / readAt use the
 * shell (via [ShellFs] on an [AdbShellRunner], persistent `sh` session); whole-file reads and
 * writes use the sync protocol (RECV / SEND), so transfers are binary-safe and fast.
 */
class AdbFs(
    val device: AdbDevice,
    override val id: String,
    override val displayName: String,
    val root: Boolean = false,
    private val shell: ShellFs = ShellFs(id, displayName, device.runner(root), homePath = "/sdcard"),
) : VirtualFs by shell {

    // No RESUME_WRITE: SEND always truncates. Reads resume through `tail -c`.
    override val capabilities: Set<Capability> = shell.capabilities - Capability.RESUME_WRITE + Capability.REMOTE

    override val homePath: String get() = if (root) "/" else "/sdcard"

    override fun has(c: Capability): Boolean = c in capabilities

    /**
     * Prefer adb sync LIST (same path as push/pull). Shell find+stat can return an empty
     * listing on some OEM / SELinux setups while RECV/SEND still work — that showed up as
     * "browse empty, transfer OK". Root targets still use the shell (sync is shell-uid).
     */
    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> {
        val dir = VPath.normalize(path)
        if (!root) {
            val sync = runCatching { listViaSync(dir) }.getOrNull()
            if (sync != null) {
                onChunk?.invoke(sync)
                return sync
            }
        }
        return shell.list(dir, onChunk)
    }

    override fun stat(path: String): FileEntry? {
        val p = VPath.normalize(path)
        if (!root) {
            runCatching { device.statSync(p) }.getOrNull()?.let { (mode, size, mtime) ->
                val name = if (p == "/" || p.isEmpty()) "/" else VPath.name(p)
                return FileEntry(
                    path = p,
                    name = name,
                    // STAT is lstat: links keep S_IFLNK. Treat links as enterable like listViaSync.
                    isDirectory = SyncWire.isDir(mode) || SyncWire.isLink(mode),
                    size = if (SyncWire.isDir(mode)) 0 else size,
                    lastModified = mtime * 1000,
                    isLink = SyncWire.isLink(mode),
                    permissions = SyncWire.modeString(mode),
                )
            }
        }
        return shell.stat(p)
    }

    private fun listViaSync(dir: String): List<FileEntry> {
        // Probe the directory itself so a missing/inaccessible path surfaces as an error
        // instead of an empty pane (LIST of a missing path may just return no dents).
        val st = device.statSync(dir) ?: throw FsException(tr("无法进入目录: $dir", "Cannot open directory: $dir"))
        if (!SyncWire.isDir(st.first) && !SyncWire.isLink(st.first)) {
            throw FsException(tr("不是文件夹: $dir", "Not a folder: $dir"))
        }
        return device.listSync(dir).map { q ->
            val isDir = SyncWire.isDir(q.mode)
            val isLink = SyncWire.isLink(q.mode)
            FileEntry(
                path = VPath.join(dir, q.name),
                name = q.name,
                isDirectory = isDir || isLink, // treat links as enterable; target checked on open
                size = if (isDir) 0 else q.size,
                lastModified = q.mtimeSec * 1000,
                isLink = isLink,
                permissions = SyncWire.modeString(q.mode),
            )
        }
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val p = VPath.normalize(path)
        if (offset > 0 || root) return shellRead(p, offset)
        return try {
            device.openRecv(p)
        } catch (e: Exception) {
            // sync runs as shell uid; fall back to the shell for odd paths
            shellRead(p, 0)
        }
    }

    private fun shellRead(p: String, offset: Long): InputStream {
        val script = if (offset > 0) "exec tail -c +${offset + 1} -- ${AdbDevice.q(p)}" else "exec cat -- ${AdbDevice.q(p)}"
        val proc = device.runner(root).start(script)
        return object : FilterInputStream(proc.inputStream) {
            private var checked = false
            private fun check() {
                if (checked) return
                checked = true
                // EOF on stdout can arrive just before the exit status: wait for it instead of exitValue().
                val code = com.ghostpanter.files.core.Proc.await(proc, 10_000)
                if (code != 0) throw FsException(tr("读取失败", "Read failed") + ": $p" + if (code == null) tr("（超时）", " (timed out)") else "")
            }
            override fun read(): Int { val b = super.read(); if (b < 0) check(); return b }
            override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); if (n < 0) check(); return n }
            override fun close() { runCatching { super.close() }; proc.destroy() }
        }
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream {
        if (offset > 0) throw FsException(tr("ADB 设备不支持续传写入", "Resumed writes are not supported over ADB"))
        val p = VPath.normalize(path)
        val parent = VPath.parent(p)
        if (!root) {
            runCatching { shell.mkdirs(parent) }
            return device.openSend(p)
        }
        // Rooted target: SEND into a shell-writable temp file, then move it in place as root.
        val tmp = "/data/local/tmp/.gf-" + java.util.UUID.randomUUID().toString().take(12)
        return device.openSend(tmp) {
            val r = device.sh("mkdir -p -- ${AdbDevice.q(parent)} && cat -- ${AdbDevice.q(tmp)} > ${AdbDevice.q(p)}; rc=\$?; rm -f -- ${AdbDevice.q(tmp)}; exit \$rc", root = true, timeoutMs = 600_000)
            if (!r.ok) throw FsException(tr("写入失败", "Write failed") + ": ${r.stdout.trim()}")
        }
    }

    override fun reset() { AdbPool.invalidate(device.serial); shell.reset() }

    companion object {
        const val PREFIX = "adb:"
        const val PREFIX_SU = "adbsu:"

        fun isAdbId(id: String) = id.startsWith(PREFIX) || id.startsWith(PREFIX_SU)
        fun serialOf(id: String) = id.removePrefix(PREFIX_SU).removePrefix(PREFIX)
        fun idFor(serial: String, root: Boolean) = (if (root) PREFIX_SU else PREFIX) + serial
    }
}
