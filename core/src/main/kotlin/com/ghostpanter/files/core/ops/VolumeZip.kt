package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.ZipParameters
import net.lingala.zip4j.model.enums.CompressionLevel
import net.lingala.zip4j.model.enums.EncryptionMethod
import java.io.File

/** Multi-volume ZIP create via zip4j (produces .zip.001, .zip.002, …). */
object VolumeZip {
    /**
     * @param partSizeBytes size of each volume (min 64 KiB). Password enables AES.
     * @return list of created part file names relative to [dstFs] parent of [dstZipPath]
     */
    fun create(
        srcFs: VirtualFs,
        sources: List<FileEntry>,
        dstFs: VirtualFs,
        dstZipPath: String,
        partSizeBytes: Long,
        password: String? = null,
        cacheDir: File,
        isCancelled: () -> Boolean = { false },
    ): List<String> {
        if (partSizeBytes < 64 * 1024) throw FsException(tr("分卷大小至少 64KB", "Volume size must be at least 64 KB"))
        cacheDir.mkdirs()
        val work = File(cacheDir, "vol-${System.currentTimeMillis()}")
        work.mkdirs()
        try {
            val locals = ArrayList<File>()
            for (e in sources) {
                if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                val local = File(work, e.name)
                if (e.isDirectory) {
                    local.mkdirs()
                    copyTree(srcFs, e.path, local)
                } else {
                    srcFs.openRead(e.path).use { ins -> local.outputStream().use { ins.copyTo(it) } }
                }
                locals += local
            }
            val outLocal = File(work, VPath.name(dstZipPath))
            val zip = if (password.isNullOrEmpty()) ZipFile(outLocal) else ZipFile(outLocal, password.toCharArray())
            val params = ZipParameters().apply {
                compressionLevel = CompressionLevel.NORMAL
                if (!password.isNullOrEmpty()) {
                    isEncryptFiles = true
                    encryptionMethod = EncryptionMethod.AES
                }
            }
            zip.createSplitZipFile(locals, params, true, partSizeBytes)
            // zip4j names: name.zip, name.z01, name.z02 OR name.zip.001 depending on version —
            // 2.11 uses .zip + .z01 style when split. Collect siblings.
            val parent = outLocal.parentFile!!
            val base = outLocal.nameWithoutExtension
            val parts = parent.listFiles()?.filter {
                it.name == outLocal.name || it.name.startsWith("$base.z") || it.name.startsWith(outLocal.name + ".")
            }?.sortedBy { it.name }.orEmpty()
            if (parts.isEmpty()) throw FsException(tr("分卷 ZIP 未生成文件", "Split ZIP produced no files"))
            val created = ArrayList<String>()
            for (p in parts) {
                if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                val dest = VPath.join(VPath.parent(dstZipPath), p.name)
                dstFs.openWrite(dest).use { out -> p.inputStream().use { it.copyTo(out) } }
                created += dest
            }
            return created
        } finally {
            work.deleteRecursively()
        }
    }

    private fun copyTree(fs: VirtualFs, path: String, dest: File) {
        dest.mkdirs()
        for (c in fs.list(path)) {
            val t = File(dest, c.name)
            if (c.isDirectory) copyTree(fs, c.path, t)
            else fs.openRead(c.path).use { ins -> t.outputStream().use { ins.copyTo(it) } }
        }
    }
}
