package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

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

    enum class Kind {
        ZIP, TAR, TAR_GZ, TAR_XZ, TAR_BZ2, TAR_ZST, TAR_LZ4,
        GZ, XZ, BZ2, ZST, LZ4, SEVEN_Z, RAR, ISO, CAB, DMG,
    }

    fun kindOf(name: String): Kind? {
        val n = name.lowercase()
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TAR_GZ
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TAR_XZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") -> Kind.TAR_BZ2
            n.endsWith(".tar.zst") || n.endsWith(".tzst") -> Kind.TAR_ZST
            n.endsWith(".tar.lz4") -> Kind.TAR_LZ4
            n.endsWith(".tar") -> Kind.TAR
            n.endsWith(".zip") || n.endsWith(".apk") || n.endsWith(".jar") || n.endsWith(".apks") || n.endsWith(".xapk") -> Kind.ZIP
            n.endsWith(".7z") -> Kind.SEVEN_Z
            n.endsWith(".rar") -> Kind.RAR
            n.endsWith(".iso") -> Kind.ISO
            n.endsWith(".cab") -> Kind.CAB
            n.endsWith(".dmg") -> Kind.DMG
            n.endsWith(".zst") || n.endsWith(".zstd") -> Kind.ZST
            n.endsWith(".lz4") -> Kind.LZ4
            n.endsWith(".gz") -> Kind.GZ
            n.endsWith(".xz") -> Kind.XZ
            n.endsWith(".bz2") -> Kind.BZ2
            else -> null
        }
    }

    fun isArchive(name: String) = kindOf(name) != null

    private val GBK: Charset = runCatching { Charset.forName("GBK") }.getOrDefault(Charsets.ISO_8859_1)

    fun decodeName(raw: ByteArray): String {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { dec.decode(ByteBuffer.wrap(raw)).toString() } catch (e: CharacterCodingException) { String(raw, GBK) }
    }

    fun safeChild(destDir: String, entryName: String): String {
        val clean = entryName.replace('\\', '/').trim('/')
        if (clean.split('/').any { it == ".." }) throw FsException(tr("压缩包内含非法路径: $entryName", "Archive contains illegal path: $entryName"))
        return VPath.join(destDir, clean)
    }

    class Progress(val name: String, val count: Int)

    fun compressZip(
        srcFs: VirtualFs, sources: List<FileEntry>, dstFs: VirtualFs, dstPath: String,
        isCancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {},
    ): Int {
        var count = 0
        dstFs.openWrite(dstPath).use { raw ->
            ZipArchiveOutputStream(raw).use { zip ->
                zip.setEncoding("UTF-8")
                zip.setUseLanguageEncodingFlag(true)
                val buf = ByteArray(128 * 1024)
                fun add(e: FileEntry, name: String) {
                    if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
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
                            while (true) { val n = input.read(buf); if (n < 0) break; zip.write(buf, 0, n) }
                        }
                        zip.closeArchiveEntry()
                        count++; onProgress(Progress(name, count))
                    }
                }
                for (s in sources) add(s, s.name)
            }
        }
        return count
    }

    val creatable = listOf(Kind.ZIP, Kind.SEVEN_Z, Kind.TAR_GZ, Kind.TAR_XZ, Kind.TAR)

    fun extensionOf(kind: Kind) = when (kind) {
        Kind.ZIP -> ".zip"; Kind.SEVEN_Z -> ".7z"; Kind.TAR -> ".tar"; Kind.TAR_GZ -> ".tar.gz"
        Kind.TAR_XZ -> ".tar.xz"; Kind.TAR_BZ2 -> ".tar.bz2"; Kind.TAR_ZST -> ".tar.zst"; Kind.TAR_LZ4 -> ".tar.lz4"
        Kind.GZ -> ".gz"; Kind.XZ -> ".xz"; Kind.BZ2 -> ".bz2"; Kind.ZST -> ".zst"; Kind.LZ4 -> ".lz4"
        Kind.RAR -> ".rar"; Kind.ISO -> ".iso"; Kind.CAB -> ".cab"; Kind.DMG -> ".dmg"
    }

    fun baseName(name: String): String {
        val k = kindOf(name) ?: return name
        val n = name.lowercase()
        val cut = listOf(
            ".tar.gz", ".tar.xz", ".tar.bz2", ".tar.zst", ".tar.lz4",
            ".tgz", ".txz", ".tbz2", ".tzst", extensionOf(k), ".apk", ".jar", ".apks", ".xapk",
        ).firstOrNull { n.endsWith(it) } ?: return name
        return name.dropLast(cut.length).ifEmpty { name + "_x" }
    }

    /** Creates an archive; non-empty [password] enables AES for ZIP (zip4j) and 7z. */
    fun compress(
        kind: Kind, srcFs: VirtualFs, sources: List<FileEntry>, dstFs: VirtualFs, dstPath: String,
        tmpDir: File, password: String? = null,
        isCancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {},
    ): Int = when (kind) {
        Kind.ZIP -> {
            if (!password.isNullOrEmpty()) {
                tmpDir.mkdirs()
                val local = File.createTempFile("enc", ".zip", tmpDir)
                try {
                    val n = ZipEdit.compressEncrypted(srcFs, sources, local, password, isCancelled, onProgress)
                    local.inputStream().use { input -> dstFs.openWrite(dstPath, 0, local.length()).use { input.copyTo(it, 256 * 1024) } }
                    n
                } finally { local.delete() }
            } else compressZip(srcFs, sources, dstFs, dstPath, isCancelled, onProgress)
        }
        Kind.TAR, Kind.TAR_GZ, Kind.TAR_XZ, Kind.TAR_BZ2 ->
            compressTar(kind, srcFs, sources, dstFs, dstPath, isCancelled, onProgress)
        Kind.SEVEN_Z -> compress7z(srcFs, sources, dstFs, dstPath, tmpDir, password, isCancelled, onProgress)
        else -> throw FsException(tr("不支持创建该格式", "Creating this format is not supported"))
    }

    private fun walk(srcFs: VirtualFs, sources: List<FileEntry>, isCancelled: () -> Boolean, visit: (FileEntry, String) -> Unit) {
        fun add(e: FileEntry, name: String) {
            if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
            visit(e, name)
            if (e.isDirectory) for (c in srcFs.list(e.path)) if (!(c.isLink && c.isDirectory)) add(c, "$name/${c.name}")
        }
        for (s in sources) add(s, s.name)
    }

    fun compressTar(
        kind: Kind, srcFs: VirtualFs, sources: List<FileEntry>, dstFs: VirtualFs, dstPath: String,
        isCancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {},
    ): Int {
        var count = 0
        dstFs.openWrite(dstPath).use { raw ->
            val comp: java.io.OutputStream = when (kind) {
                Kind.TAR_GZ -> org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream(java.io.BufferedOutputStream(raw, 256 * 1024))
                Kind.TAR_XZ -> org.apache.commons.compress.compressors.xz.XZCompressorOutputStream(java.io.BufferedOutputStream(raw, 256 * 1024))
                Kind.TAR_BZ2 -> org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(java.io.BufferedOutputStream(raw, 256 * 1024))
                else -> java.io.BufferedOutputStream(raw, 256 * 1024)
            }
            org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(comp, "UTF-8").use { tar ->
                tar.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.BIGNUMBER_POSIX)
                val buf = ByteArray(128 * 1024)
                walk(srcFs, sources, isCancelled) { e, name ->
                    val te = org.apache.commons.compress.archivers.tar.TarArchiveEntry(if (e.isDirectory) "$name/" else name)
                    if (e.lastModified > 0) te.setModTime(e.lastModified)
                    if (!e.isDirectory) te.size = e.size
                    tar.putArchiveEntry(te)
                    if (!e.isDirectory) {
                        var left = e.size
                        srcFs.openRead(e.path).use { input ->
                            while (left > 0) {
                                if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break
                                tar.write(buf, 0, n); left -= n
                            }
                        }
                        if (left != 0L) throw FsException(tr("文件在压缩时被修改: ${e.path}", "File changed while compressing: ${e.path}"))
                        count++; onProgress(Progress(name, count))
                    }
                    tar.closeArchiveEntry()
                }
                tar.finish()
            }
        }
        return count
    }

    fun compress7z(
        srcFs: VirtualFs, sources: List<FileEntry>, dstFs: VirtualFs, dstPath: String, tmpDir: File,
        password: String? = null,
        isCancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {},
    ): Int {
        var count = 0
        tmpDir.mkdirs()
        val tmp = File.createTempFile("gf-7z", ".7z", tmpDir)
        try {
            val sevenZ = if (!password.isNullOrEmpty()) {
                org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(tmp, password.toCharArray())
            } else org.apache.commons.compress.archivers.sevenz.SevenZOutputFile(tmp)
            sevenZ.use { z ->
                val buf = ByteArray(128 * 1024)
                walk(srcFs, sources, isCancelled) { e, name ->
                    val ze = org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry()
                    ze.name = name
                    ze.isDirectory = e.isDirectory
                    if (e.lastModified > 0) { ze.hasLastModifiedDate = true; ze.lastModifiedDate = Date(e.lastModified) }
                    z.putArchiveEntry(ze)
                    if (!e.isDirectory) {
                        srcFs.openRead(e.path).use { input ->
                            while (true) {
                                if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                                val n = input.read(buf); if (n < 0) break
                                z.write(buf, 0, n)
                            }
                        }
                        count++; onProgress(Progress(name, count))
                    }
                    z.closeArchiveEntry()
                }
            }
            tmp.inputStream().use { input -> dstFs.openWrite(dstPath, 0, tmp.length()).use { input.copyTo(it, 256 * 1024) } }
        } finally { tmp.delete() }
        return count
    }

    fun extract(
        srcFs: VirtualFs, archivePath: String, dstFs: VirtualFs, destDir: String,
        localFile: File? = null, tmpDir: File? = null, password: String? = null,
        isCancelled: () -> Boolean = { false }, onProgress: (Progress) -> Unit = {},
    ): Int {
        val kind = kindOf(archivePath) ?: throw FsException(tr("不支持的压缩格式", "Unsupported archive format"))
        dstFs.mkdirs(destDir)
        var count = 0
        val buf = ByteArray(128 * 1024)
        fun write(name: String, input: InputStream, isDir: Boolean, mtime: Long) {
            if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
            val target = safeChild(destDir, name)
            if (isDir) { dstFs.mkdirs(target); return }
            dstFs.mkdirs(VPath.parent(target))
            dstFs.openWrite(target).use { out ->
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
            }
            if (mtime > 0) runCatching { dstFs.setLastModified(target, mtime) }
            count++; onProgress(Progress(name, count))
        }
        fun drain(ais: ArchiveInputStream<*>) {
            while (true) {
                val e = ais.nextEntry ?: break
                if (!ais.canReadEntryData(e)) throw FsException(tr("不支持的条目（可能已加密）: ${e.name}", "Unsupported entry (maybe encrypted): ${e.name}"))
                val name = if (e is ZipArchiveEntry && !e.generalPurposeBit.usesUTF8ForNames()) decodeName(e.rawName) else e.name
                write(name, ais, e.isDirectory, e.lastModifiedDate?.time ?: 0)
            }
        }
        val baseName = VPath.name(archivePath)
        when (kind) {
            Kind.ZIP -> {
                if (!password.isNullOrEmpty()) {
                    val (file, temp) = spool(srcFs, archivePath, localFile, tmpDir)
                    try {
                        val z = net.lingala.zip4j.ZipFile(file, password.toCharArray())
                        for (h in z.fileHeaders) {
                            if (isCancelled()) throw FsException(tr("已取消", "Cancelled"))
                            val name = h.fileName.replace('\\', '/')
                            if (h.isDirectory) { write(name, EMPTY, true, 0); continue }
                            z.getInputStream(h).use { ins -> write(name, ins, false, h.lastModifiedTime) }
                        }
                    } finally { if (temp) file.delete() }
                } else srcFs.openRead(archivePath).use { raw ->
                    drain(ZipArchiveInputStream(BufferedInputStream(raw, 64 * 1024), "UTF-8", true, true))
                }
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
            Kind.RAR -> {
                val (file, temp) = spool(srcFs, archivePath, localFile, tmpDir)
                try {
                    // junrar: RAR 1.5–4.x. RAR5 signature starts with Rar!\x1a\x07\x01
                    val sig = ByteArray(8)
                    file.inputStream().use { it.read(sig) }
                    val isRar5 = sig.size >= 8 && sig[0] == 'R'.code.toByte() && sig[6] == 1.toByte()
                    if (isRar5) {
                        throw FsException(
                            tr("检测到 RAR5。当前内置 junrar 仅稳定支持 RAR4；请用电脑 7-Zip 转成 zip/7z，或等后续版本内置 RAR5 引擎", "RAR5 detected. The built-in junrar only supports RAR4; convert to zip/7z with 7-Zip on a PC"),
                        )
                    }
                    val arc = try { Archive(file, password) } catch (e: Exception) {
                        throw FsException(tr("无法打开 RAR: ${e.message}", "Cannot open RAR: ${e.message}"), e)
                    }
                    arc.use {
                        for (h in arc.fileHeaders) {
                            val name = h.fileName.replace('\\', '/')
                            if (h.isDirectory) { write(name, EMPTY, true, 0); continue }
                            arc.getInputStream(h).use { ins -> write(name, ins, false, h.mTime?.time ?: 0) }
                        }
                    }
                } finally { if (temp) file.delete() }
            }
            Kind.ZST, Kind.TAR_ZST -> count = ExtraArchives.extractZstd(srcFs, archivePath, dstFs, destDir, isCancelled)
            Kind.LZ4, Kind.TAR_LZ4 -> count = ExtraArchives.extractLz4(srcFs, archivePath, dstFs, destDir, isCancelled)
            Kind.ISO -> {
                val (file, temp) = spool(srcFs, archivePath, localFile, tmpDir)
                try { count = ExtraArchives.extractIso(file, dstFs, destDir, isCancelled) }
                finally { if (temp) file.delete() }
            }
            Kind.CAB -> {
                val (file, temp) = spool(srcFs, archivePath, localFile, tmpDir)
                try { count = ExtraArchives.extractCab(file, dstFs, destDir, isCancelled) }
                finally { if (temp) file.delete() }
            }
            Kind.DMG -> {
                val (file, temp) = spool(srcFs, archivePath, localFile, tmpDir)
                try { count = ExtraArchives.extractDmg(file, dstFs, destDir, isCancelled) }
                finally { if (temp) file.delete() }
            }
        }
        return count
    }

    private val EMPTY: InputStream = object : InputStream() { override fun read(): Int = -1 }

    private fun spool(fs: VirtualFs, path: String, localFile: File?, tmpDir: File?): Pair<File, Boolean> {
        if (localFile != null && localFile.isFile) return localFile to false
        val dir = tmpDir ?: File(System.getProperty("java.io.tmpdir"))
        val f = File.createTempFile("arc", ".tmp", dir)
        fs.openRead(path).use { input -> f.outputStream().use { input.copyTo(it, 128 * 1024) } }
        return f to true
    }
}
