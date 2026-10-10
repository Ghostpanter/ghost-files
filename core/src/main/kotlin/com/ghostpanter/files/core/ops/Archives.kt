package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import com.github.junrar.Archive
import org.apache.commons.compress.archivers.ArchiveInputStream
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Date

/** Archive create/extract working on any [VirtualFs] (local, root, LAN). */
object Archives {

    enum class Kind { ZIP, TAR, TAR_GZ, TAR_XZ, TAR_BZ2, GZ, XZ, BZ2, SEVEN_Z, RAR }

    fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TAR_GZ
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TAR_XZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> Kind.TAR_BZ2
            n.endsWith(".tar") -> Kind.TAR
            n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar") || n.endsWith(".apks") || n.endsWith(".xapk") -> Kind.ZIP
            n.endsWith(".7z") -> Kind.SEVEN_Z
            n.endsWith(".rar") -> Kind.RAR
            n.endsWith(".gz") -> Kind.GZ
            n.endsWith(".xz") -> Kind.XZ
            n.endsWith(".bz2") -> Kind.BZ2
            else -> null
        }
    }

    fun isArchive(name: String) = kindOf(name) != null

    private val GBK: Charset = runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.ISO_8859_1)

    /** Zip entries without the UTF-8 flag are usually GBK when made on Chinese Windows. */
    fun decodeName(raw: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { dec.decode(ByteBuffer.wrap(raw)).toString() } catch (e: CharacterCodingException) { String(raw, GBK) }
    }

    /** Rejects zip-slip entries ("../../x"). */
    fun safeChild(destDir: String, entryName: String): String {
        val clean = entryName.replace('\\', '/').trim('/')
        if (clean.split('/').any { it == ".." }) throw FsException("压缩包内含非法路径: $entryName")
        return VPath.join(destDir, clean)
    }

    class Progress(val name: String, val count: Int)

    fun compressZip(
        srcFs: VirtualFs,
        sources: List<FileEntry>,
        dstFs: VirtualFs,
        dstPath: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Int {
        var count = 0
        dstFs.openWrite(dstPath).use { raw ->
            ZipArchiveOutputStream(raw).use { zip ->
                zip.setEncoding("UTF-8")
                zip.setUseLanguageEncodingFlag(true)
                val buf = ByteArray(128 * 1024)
                fun add(e: FileEntry, name: String) {
                    if (isCancelled()) throw FsException("已取消")
                    if (e.isDirectory) {
                        val de = ZipArchiveEntry("$name/")
                        if (e.lastModified > 0) de.time = e.lastModified
                        zip.putArchiveEntry(de); zip.closeArchiveEntry()
                        for (c in srcFs.list(e.path)) if (!(c.isLink && c.isDirectory)) add(c, "$name/${c.name}")
                    } else {
                        val fe = ZipArchiveEntry(name)
                        if (e.lastModified > 0) fe.time = e.lastModified
                        zip.putArchiveEntry(fe)
                        srcFs.openRead(e.path).use { input ->
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                zip.write(buf, 0, n)
                            }
                        }
                        zip.closeArchiveEntry()
                        count++
                        onProgress(Progress(name, count))
                    }
                }
                for (s in sources) add(s, s.name)
            }
        }
        return count
    }

    /**
     * Extracts [archivePath] into [destDir]. 7z needs random access: pass
     * [localFile] when the archive is a plain local file, otherwise it is
     * spooled to [tmpDir] first.
     */
    fun extract(
        srcFs: VirtualFs,
        archivePath: String,
        dstFs: VirtualFs,
        destDir: String,
        localFile: File? = null,
        tmpDir: File? = null,
        password: String? = null,
        isCancelled: () -> Boolean = { false },
        onProgress: (Progress) -> Unit = {},
    ): Int {
        val kind = kindOf(archivePath) ?: throw FsException("不支持的压缩格式")
        dstFs.mkdirs(destDir)
        var count = 0
        val buf = ByteArray(128 * 1024)
        fun write(name: String, input: InputStream, isDir: Boolean, mtime: Long) {
            if (isCancelled()) throw FsException("已取消")
            val target = safeChild(destDir, name)
            if (isDir) { dstFs.mkdirs(target); return }
            dstFs.mkdirs(VPath.parent(target))
            dstFs.openWrite(target).use { out ->
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            }
            if (mtime > 0) runCatching { dstFs.setLastModified(target, mtime) }
            count++
            onProgress(Progress(name, count))
        }
        fun drain(ais: ArchiveInputStream<*>) {
            while (true) {
                val e = ais.nextEntry ?: break
                if (!ais.canReadEntryData(e)) throw FsException("不支持的条目（可能已加密）: ${e.name}")
                val name = if (e is ZipArchiveEntry && !e.generalPurposeBit.usesUTF8ForNames()) decodeName(e.rawName) else e.name
                write(name, ais, e.isDirectory, e.lastModifiedDate?.time ?: 0)
            }
        }
        val baseName = VPath.name(archivePath)
        when (kind) {
            Kind.ZIP -> srcFs.openRead(archivePath).use { raw ->
                drain(ZipArchiveInputStream(BufferedInputStream(raw, 64 * 1024), "UTF-8", true, true))
            }
            Kind.TAR -> srcFs.openRead(archivePath).use { drain(TarArchiveInputStream(BufferedInputStream(it))) }
            Kind.TAR_GZ -> srcFs.openRead(archivePath).use { drain(TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(it)))) }
            Kind.TAR_XZ -> srcFs.openRead(archivePath).use { drain(TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(it)))) }
            Kind.TAR_BZ2 -> srcFs.openRead(archivePath).use { drain(TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(it)))) }
            Kind.GZ -> srcFs.openRead(archivePath).use { write(baseName.substringBeforeLast('.'), GzipCompressorInputStream(BufferedInputStream(it)), false, 0) }
            Kind.XZ -> srcFs.openRead(archivePath).use { write(baseName.substringBeforeLast('.'), XZCompressorInputStream(BufferedInputStream(it)), false, 0) }
            Kind.BZ2 -> srcFs.openRead(archivePath).use { write(baseName.substringBeforeLast('.'), BZip2CompressorInputStream(BufferedInputStream(it)), false, 0) }
            Kind.SEVEN_Z -> {
                val (file, temp) = spool(srcFs, archivePath, localFile, tmpDir)
                try {
                    val b = SevenZFile.builder().setFile(file)
                    if (!password.isNullOrEmpty()) b.setPassword(password.toCharArray())
                    b.get().use { z ->
                        while (true) {
                            val e = z.nextEntry ?: break
                            val input = object : InputStream() {
                                override fun read(): Int = z.read()
                                override fun read(b: ByteArray, off: Int, len: Int) = z.read(b, off, len)
                            }
                            write(e.name, input, e.isDirectory, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0)
                        }
                    }
                } finally { if (temp) file.delete() }
            }
            Kind.RAR -> srcFs.openRead(archivePath).use { raw ->
                val arc = try { Archive(BufferedInputStream(raw), password) } catch (e: Exception) {
                    throw FsException("无法打开 RAR（暂不支持 RAR5/加密卷）: ${e.message}", e)
                }
                arc.use {
                    for (h in arc.fileHeaders) {
                        val name = h.fileName.replace('\\', '/')
                        if (h.isDirectory) { write(name, EMPTY, true, 0); continue }
                        arc.getInputStream(h).use { ins -> write(name, ins, false, h.mTime?.time ?: 0) }
                    }
                }
            }
        }
        return count
    }

    private val EMPTY: InputStream = object : InputStream() {
        override fun read(): Int = -1
    }

    private fun spool(fs: VirtualFs, path: String, localFile: File?, tmpDir: File?): Pair<File, Boolean> {
        if (localFile != null && localFile.isFile) return localFile to false
        val dir = tmpDir ?: File(System.getProperty("java.io.tmpdir"))
        val f = File.createTempFile("arc", ".tmp", dir)
        fs.openRead(path).use { input -> f.outputStream().use { input.copyTo(it, 128 * 1024) } }
        return f to true
    }

    @Suppress("unused")
    private fun Date?.millis() = this?.time ?: 0L
}
