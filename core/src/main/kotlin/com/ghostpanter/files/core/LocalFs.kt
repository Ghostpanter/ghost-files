package com.ghostpanter.files.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission

/** Plain java.io access with the app's own permissions. */
class LocalFs(
    override val id: String = ID,
    override val displayName: String = "本机",
    override val homePath: String = VPath.ROOT,
) : VirtualFs {

    override val capabilities = setOf(Capability.RESUME_READ, Capability.RESUME_WRITE, Capability.RENAME_MOVE)

    private fun f(path: String) = File(VPath.normalize(path))

    private fun entryOf(file: File, detailed: Boolean): FileEntry {
        val p = file.toPath()
        val isLink = runCatching { Files.isSymbolicLink(p) }.getOrDefault(false)
        var perms: String? = null
        var owner: String? = null
        var group: String? = null
        if (detailed) {
            runCatching {
                val a = Files.readAttributes(p, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                perms = (if (file.isDirectory) "d" else if (isLink) "l" else "-") + permString(a.permissions())
                owner = a.owner()?.name
                group = a.group()?.name
            }
        }
        return FileEntry(
            path = VPath.normalize(file.path),
            name = file.name,
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) 0 else file.length(),
            lastModified = file.lastModified(),
            isLink = isLink,
            permissions = perms,
            owner = owner,
            group = group,
        )
    }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> {
        val dir = f(path)
        if (!dir.exists()) throw FsException(tr("目录不存在: $path", "Directory not found: $path"))
        if (!dir.isDirectory) throw FsException(tr("不是目录: $path", "Not a directory: $path"))
        val files = dir.listFiles() ?: throw FsException(tr("无权限读取: $path", "Permission denied: $path"))
        val out = ArrayList<FileEntry>(files.size)
        val chunk = ArrayList<FileEntry>()
        for (file in files) {
            val e = entryOf(file, false)
            out.add(e)
            if (onChunk != null) {
                chunk.add(e)
                if (chunk.size >= 500) { onChunk(ArrayList(chunk)); chunk.clear() }
            }
        }
        if (onChunk != null && chunk.isNotEmpty()) onChunk(chunk)
        return out
    }

    override fun stat(path: String): FileEntry? {
        val file = f(path)
        val exists = file.exists() || runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        return if (exists) entryOf(file, true) else null
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val file = f(path)
        if (!file.isFile) throw FsException(tr("不是文件或无法读取: $path", "Not a readable file: $path"))
        val input = FileInputStream(file)
        if (offset > 0) input.channel.position(offset)
        return input
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream {
        val file = f(path)
        file.parentFile?.let { if (!it.exists()) it.mkdirs() }
        if (offset > 0) {
            RandomAccessFile(file, "rw").use { it.setLength(offset) }
            return FileOutputStream(file, true)
        }
        return FileOutputStream(file, false)
    }

    override fun mkdirs(path: String) {
        val file = f(path)
        if (file.isDirectory) return
        if (!file.mkdirs() && !file.isDirectory) throw FsException(tr("无法创建目录: $path", "Cannot create directory: $path"))
    }

    override fun delete(path: String, recursive: Boolean) {
        val file = f(path)
        val isLink = runCatching { Files.isSymbolicLink(file.toPath()) }.getOrDefault(false)
        if (!file.exists() && !isLink) return
        val ok = if (recursive && file.isDirectory && !isLink) file.deleteRecursively() else file.delete()
        if (!ok) throw FsException(tr("删除失败: $path", "Delete failed: $path"))
    }

    override fun rename(from: String, to: String) {
        val src = f(from)
        val dst = f(to)
        if (!src.exists()) throw FsException(tr("源不存在: $from", "Source not found: $from"))
        // Case-only rename on case-insensitive storage (Android /sdcard): dst "exists" but is src.
        val sameFile = dst.exists() && runCatching { Files.isSameFile(src.toPath(), dst.toPath()) }.getOrDefault(false)
        if (sameFile && src.name != dst.name && src.parentFile == dst.parentFile) {
            val tmp = File(src.parentFile, ".gf-rename-" + System.nanoTime())
            if (src.renameTo(tmp) && tmp.renameTo(dst)) return
            if (tmp.exists()) tmp.renameTo(src)
            throw FsException(tr("重命名失败: $from -> $to", "Rename failed: $from -> $to"))
        }
        if (dst.exists()) throw FsException(tr("目标已存在: $to", "Target exists: $to"))
        if (src.renameTo(dst)) return
        try {
            Files.move(src.toPath(), dst.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            throw FsException(tr("重命名/移动失败: $from -> $to", "Rename/move failed: $from -> $to"), e)
        }
    }

    override fun setLastModified(path: String, millis: Long) {
        if (millis > 0) f(path).setLastModified(millis)
    }

    companion object {
        const val ID = "local"

        fun permString(p: Set<PosixFilePermission>): String {
            fun c(perm: PosixFilePermission, ch: Char) = if (perm in p) ch else '-'
            return buildString {
                append(c(PosixFilePermission.OWNER_READ, 'r')); append(c(PosixFilePermission.OWNER_WRITE, 'w')); append(c(PosixFilePermission.OWNER_EXECUTE, 'x'))
                append(c(PosixFilePermission.GROUP_READ, 'r')); append(c(PosixFilePermission.GROUP_WRITE, 'w')); append(c(PosixFilePermission.GROUP_EXECUTE, 'x'))
                append(c(PosixFilePermission.OTHERS_READ, 'r')); append(c(PosixFilePermission.OTHERS_WRITE, 'w')); append(c(PosixFilePermission.OTHERS_EXECUTE, 'x'))
            }
        }
    }
}
