package com.ghostpanter.files.fs

import android.os.ParcelFileDescriptor
import com.ghostpanter.files.core.NioListing
import java.io.File
import kotlin.concurrent.thread

/**
 * Runs inside Shizuku's process (shell uid), started by Shizuku.bindUserService. Plain java.io / java.nio, so a
 * directory listing is one Binder call instead of a `sh` process.
 */
class FileService : IFileService.Stub() {
    override fun destroy() { System.exit(0) }

    override fun version(): Int = VERSION

    private fun pipe(text: () -> String): ParcelFileDescriptor {
        val (r, w) = ParcelFileDescriptor.createPipe()
        thread(isDaemon = true, name = "fs-svc-pipe") {
            ParcelFileDescriptor.AutoCloseOutputStream(w).use { o ->
                val s = runCatching(text).getOrElse { "${NioListing.ERR}\t" + (it.message ?: it.javaClass.simpleName) }
                o.write(s.toByteArray(Charsets.UTF_8))
            }
        }
        return r
    }

    override fun list(path: String): ParcelFileDescriptor = pipe { NioListing.list(path) }

    override fun stat(path: String): String = runCatching { NioListing.stat(path) }.getOrElse { "${NioListing.ERR}\t${it.message}" }

    override fun openRead(path: String): ParcelFileDescriptor? = runCatching {
        ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
    }.getOrNull()

    override fun openWrite(path: String, offset: Long): ParcelFileDescriptor? = runCatching {
        val f = File(path)
        f.parentFile?.mkdirs()
        if (offset > 0) {
            java.io.RandomAccessFile(f, "rw").use { it.setLength(offset) }
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_APPEND)
        } else ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE)
    }.getOrNull()

    override fun mkdirs(path: String): String {
        val f = File(path)
        return if (f.isDirectory || f.mkdirs() || f.isDirectory) "" else "mkdir failed: $path"
    }

    override fun remove(path: String, recursive: Boolean): String = NioListing.delete(File(path), recursive) ?: ""

    override fun rename(from: String, to: String): String {
        val a = File(from); val b = File(to)
        if (b.exists() || java.nio.file.Files.isSymbolicLink(b.toPath())) return "EEXIST"
        return if (a.renameTo(b)) "" else "FALLBACK"
    }

    companion object { const val VERSION = 1 }
}
