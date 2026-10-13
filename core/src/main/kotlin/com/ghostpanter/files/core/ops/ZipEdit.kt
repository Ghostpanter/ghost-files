package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.CompressionMethod
import net.lingala.zip4j.model.enums.EncryptionMethod
import net.lingala.zip4j.model.enums.AesKeyStrength
import java.io.File

/**
 * Encrypted ZIP create (AES-256) and in-archive add/update/delete via zip4j
 * (avoids full recompress when the archive is a local random-access file).
 */
object ZipEdit {
    fun compressEncrypted(
        srcFs: VirtualFs,
        sources: List<FileEntry>,
        dstFile: File,
        password: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (Archives.Progress) -> Unit = {},
    ): Int {
        if (password.isEmpty()) throw FsException("密码不能为空")
        if (dstFile.exists()) dstFile.delete()
        val zip = ZipFile(dstFile, password.toCharArray())
        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            compressionLevel = CompressionLevel.NORMAL
            isEncryptFiles = true
            encryptionMethod = EncryptionMethod.AES
            aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
        }
        var count = 0
        val tmpRoot = File(dstFile.parentFile, ".gf-zip-src-${System.nanoTime()}").apply { mkdirs() }
        try {
            fun stage(e: FileEntry, rel: String) {
                if (isCancelled()) throw FsException("已取消")
                if (e.isDirectory) {
                    for (c in srcFs.list(e.path)) stage(c, "$rel/${c.name}")
                } else {
                    val out = File(tmpRoot, rel)
                    out.parentFile?.mkdirs()
                    srcFs.openRead(e.path).use { input -> out.outputStream().use { input.copyTo(it) } }
                    params.fileNameInZip = rel.trimStart('/')
                    zip.addFile(out, params)
                    count++
                    onProgress(Archives.Progress(rel, count))
                }
            }
            for (s in sources) {
                if (s.isDirectory) stage(s, s.name)
                else {
                    val out = File(tmpRoot, s.name)
                    srcFs.openRead(s.path).use { input -> out.outputStream().use { input.copyTo(it) } }
                    params.fileNameInZip = s.name
                    zip.addFile(out, params)
                    count++
                    onProgress(Archives.Progress(s.name, count))
                }
            }
        } finally {
            tmpRoot.deleteRecursively()
        }
        return count
    }

    fun listEntries(zipFile: File, password: String? = null): List<String> {
        val z = if (password.isNullOrEmpty()) ZipFile(zipFile) else ZipFile(zipFile, password.toCharArray())
        return z.fileHeaders.map { it.fileName }
    }

    fun addOrUpdate(zipFile: File, localFile: File, entryName: String, password: String? = null) {
        val z = if (password.isNullOrEmpty()) ZipFile(zipFile) else ZipFile(zipFile, password.toCharArray())
        val params = ZipParameters().apply {
            compressionMethod = CompressionMethod.DEFLATE
            fileNameInZip = entryName
            if (!password.isNullOrEmpty()) {
                isEncryptFiles = true
                encryptionMethod = EncryptionMethod.AES
                aesKeyStrength = AesKeyStrength.KEY_STRENGTH_256
            }
        }
        if (z.fileHeaders.any { it.fileName == entryName || it.fileName == "$entryName/" }) {
            z.removeFile(entryName)
        }
        z.addFile(localFile, params)
    }

    fun removeEntries(zipFile: File, entryNames: List<String>, password: String? = null) {
        val z = if (password.isNullOrEmpty()) ZipFile(zipFile) else ZipFile(zipFile, password.toCharArray())
        for (n in entryNames) {
            val headers = z.fileHeaders.filter { it.fileName == n || it.fileName.startsWith("$n/") }
            for (h in headers.toList()) z.removeFile(h)
        }
    }

    /** Spool remote archive to [tmp], edit, write back. */
    fun withLocalZip(
        fs: VirtualFs, archivePath: String, tmpDir: File, password: String? = null,
        edit: (File) -> Unit,
    ) {
        tmpDir.mkdirs()
        val local = File(tmpDir, "edit-${VPath.name(archivePath)}")
        fs.openRead(archivePath).use { input -> local.outputStream().use { input.copyTo(it) } }
        edit(local)
        fs.openWrite(archivePath, 0, local.length()).use { out -> local.inputStream().use { it.copyTo(out) } }
        local.delete()
    }
}
