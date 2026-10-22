package com.ghostpanter.files.core

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions

/**
 * java.nio implementation of what [ShellFs] gets from `stat` / `find`, in the same text format so the same parser
 * reads both. Used inside the Shizuku UserService (runs as the shell uid) instead of spawning `sh` per call.
 */
object NioListing {
    const val OK = "OK"
    const val ERR = "ERR"

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ')

    private fun line(p: Path, name: String, linkDirs: MutableSet<String>?): String? = try {
        val a = Files.readAttributes(p, PosixFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val type = when { a.isSymbolicLink -> "symbolic link"; a.isDirectory -> "directory"; a.isRegularFile -> "regular file"; else -> "other" }
        val tc = when { a.isSymbolicLink -> 'l'; a.isDirectory -> 'd'; a.isRegularFile -> '-'; else -> '?' }
        if (a.isSymbolicLink && linkDirs != null && runCatching { Files.isDirectory(p) }.getOrDefault(false)) linkDirs += name
        val owner = runCatching { a.owner().name }.getOrDefault("?")
        val group = runCatching { a.group().name }.getOrDefault("?")
        "$type\t$tc${PosixFilePermissions.toString(a.permissions())}\t${a.size()}\t${a.lastModifiedTime().toMillis() / 1000}\t${clean(owner)}\t${clean(group)}\t./$name"
    } catch (_: UnsupportedOperationException) {
        basic(p.toFile(), name)
    } catch (_: Exception) {
        basic(p.toFile(), name)
    }

    private fun basic(f: File, name: String): String? {
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return null
        val d = f.isDirectory
        return "${if (d) "directory" else "regular file"}\t${if (d) "d" else "-"}?????????\t${if (d) 0 else f.length()}\t${f.lastModified() / 1000}\t?\t?\t./$name"
    }

    /** "OK\n" + ShellFs listing text, or "ERR\t<reason>\t<path>". Empty folders are just "OK\n". */
    fun list(path: String): String {
        val dir = File(path)
        val names = dir.list() ?: return "$ERR\t" + (if (!dir.exists()) "ENOENT" else if (!dir.isDirectory) "ENOTDIR" else "EACCES") + "\t" + clean(path)
        val sb = StringBuilder(OK).append('\n')
        val links = HashSet<String>()
        val base = dir.toPath()
        for (n in names) line(base.resolve(n), n, links)?.let { sb.append(it).append('\n') }
        sb.append(ShellFs.SEP).append('\n')
        for (l in links) sb.append("./").append(l).append('\n')
        return sb.toString()
    }

    /** "OK\n<stat line>\n[--GHOST-ISDIR--]" or "ERR\tENOENT". */
    fun stat(path: String): String {
        val p = File(path).toPath()
        val l = line(p, p.fileName?.toString() ?: "/", null) ?: return "$ERR\tENOENT"
        val isDir = runCatching { Files.isDirectory(p) }.getOrDefault(false)
        return "$OK\n$l\n" + if (isDir) ShellFs.SEP_DIR + "\n" else ""
    }

    /** Parses [list] output back into entries. Throws [FsException] for ERR. */
    fun parseList(dir: String, text: String): List<FileEntry> {
        if (!text.startsWith(OK)) throw FsException(errText(text, dir))
        return ShellFs.parseListing(VPath.normalize(dir), text.substringAfter('\n', ""))
    }

    fun parseStat(path: String, text: String): FileEntry? {
        if (!text.startsWith(OK)) return null
        val p = VPath.normalize(path)
        val lines = text.lines().drop(1).filter { it.isNotEmpty() }
        val l = lines.firstOrNull { it != ShellFs.SEP_DIR } ?: return null
        val e = ShellFs.parseStatLine(VPath.parent(p), l, emptySet(), p) ?: return null
        return if (ShellFs.SEP_DIR in lines && !e.isDirectory) e.copy(isDirectory = true, size = 0) else e
    }

    fun errText(text: String, path: String): String = when (text.split('\t').getOrNull(1)) {
        "ENOENT" -> tr("不存在: $path", "Not found: $path")
        "ENOTDIR" -> tr("不是文件夹: $path", "Not a folder: $path")
        "EACCES" -> tr("无法进入目录（没有权限）: $path", "Cannot open directory (permission denied): $path")
        else -> text.substringAfter('\t').ifEmpty { tr("失败", "failed") }
    }

    /** Deletes [f]; directories recursively when [recursive]. Symlinks are removed, never followed. Null = done. */
    fun delete(f: File, recursive: Boolean): String? {
        val p = f.toPath()
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) return null
        fun rm(x: Path) {
            if (Files.isDirectory(x, LinkOption.NOFOLLOW_LINKS)) Files.newDirectoryStream(x).use { s -> s.forEach { rm(it) } }
            Files.delete(x)
        }
        return try {
            if (recursive) rm(p) else Files.delete(p)
            null
        } catch (e: java.nio.file.DirectoryNotEmptyException) { tr("文件夹不为空", "Folder not empty") }
        catch (e: Exception) { e.message ?: e.javaClass.simpleName }
    }
}
