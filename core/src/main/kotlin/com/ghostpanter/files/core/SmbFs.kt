package com.ghostpanter.files.core

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mserref.NtStatus
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * SMB2/3 via smbj. One TCP connection + session per server is shared by
 * listing and transfers (SMB multiplexes requests); a broken connection is
 * dropped and transparently re-established on the next call.
 */
class SmbFs(
    private val config: ServerConfig,
    private val timeouts: NetTimeouts = NetTimeouts(),
    /** Owned by one transfer job: aborting a stream may tear down the whole connection. */
    private val dedicated: Boolean = false,
) : VirtualFs {

    override val id = config.fsId
    override val displayName = config.name
    override val capabilities = setOf(
        Capability.RESUME_READ, Capability.RESUME_WRITE, Capability.SERVER_COPY,
        Capability.RENAME_MOVE, Capability.REMOTE,
    )

    private val shares: List<String> = config.path.split(',', ';')
        .map { it.trim().trim('/', '\\') }.filter { it.isNotEmpty() }

    override val homePath: String = if (shares.size == 1) "/" + shares[0] else VPath.ROOT

    private val connector = SmbConnector(timeouts)

    override val stallHintMs: Long get() = timeouts.smbRequestMs + 15_000

    @Volatile private var session: Session? = null
    private val diskShares = ConcurrentHashMap<String, DiskShare>()
    private val lock = Any()

    private fun session(): Session = synchronized(lock) {
        val s = session
        if (s != null && s.connection.isConnected) return s
        diskShares.clear()
        runCatching { s?.close() }
        val ns = if (config.anonymous) {
            connector.open(config.host, config.effectivePort, "", "", "")
        } else {
            connector.open(config.host, config.effectivePort, config.username, config.password, config.domain)
        }
        session = ns
        ns
    }

    private fun share(name: String): DiskShare = synchronized(lock) {
        val existing = diskShares[name]
        if (existing != null && existing.isConnected && session?.connection?.isConnected == true) return existing
        diskShares.remove(name)
        val sh = session().connectShare(name) as? DiskShare ?: throw FsException("$name 不是磁盘共享")
        diskShares[name] = sh
        sh
    }

    private data class Loc(val share: String, val path: String)

    private fun loc(path: String): Loc? {
        val n = VPath.normalize(path)
        if (n == VPath.ROOT) return null
        val parts = n.trim('/').split('/', limit = 2)
        return Loc(parts[0], if (parts.size > 1) parts[1].replace('/', '\\') else "")
    }

    /**
     * Runs [block]; when the connection turns out to be dead (server idle disconnect, Wi-Fi
     * hiccup, reset) it reconnects and runs the (idempotent) block once more, so callers and the
     * transfer engine don't see a failure for a merely stale connection.
     */
    private inline fun <T> io(what: String, retryOnDrop: Boolean = true, block: () -> T): T {
        var first = true
        while (true) {
            try {
                return block()
            } catch (e: SMBApiException) {
                if (first && retryOnDrop && isSessionGone(e.status)) { first = false; reset(); continue }
                throw FsException("$what: ${statusText(e.status)}", e)
            } catch (e: FsException) {
                throw e
            } catch (e: Exception) {
                val drop = isConnectionError(e)
                if (drop) reset()
                if (first && retryOnDrop && drop) { first = false; continue }
                throw FsException("$what: ${ErrorText.describe(e)}", e)
            }
        }
    }

    private fun isSessionGone(s: NtStatus) = s == NtStatus.STATUS_NETWORK_SESSION_EXPIRED ||
        s == NtStatus.STATUS_USER_SESSION_DELETED || s == NtStatus.STATUS_NETWORK_NAME_DELETED

    private fun statusText(s: NtStatus): String = when (s) {
        NtStatus.STATUS_ACCESS_DENIED -> "拒绝访问"
        NtStatus.STATUS_LOGON_FAILURE -> "用户名或密码错误"
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "不存在"
        NtStatus.STATUS_OBJECT_NAME_COLLISION -> "已存在"
        NtStatus.STATUS_BAD_NETWORK_NAME -> "共享名不存在"
        NtStatus.STATUS_SHARING_VIOLATION -> "文件正被其他程序占用"
        NtStatus.STATUS_CANNOT_DELETE -> "文件为只读或正在使用，无法删除"
        NtStatus.STATUS_DISK_FULL -> "空间不足"
        NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> "目录非空"
        else -> s.name
    }

    private fun shareRoots() = shares.map { FileEntry("/$it", it, true, 0, 0) }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> = io("列出 $path") {
        val l = loc(path)
        if (l == null) {
            if (shares.isEmpty()) {
                // No shares configured: enumerate them (SRVSVC NetShareEnum) like Explorer does.
                val found = com.ghostpanter.files.core.lan.SmbShares.userVisible(
                    com.ghostpanter.files.core.lan.SmbShares.enumerate(session())
                )
                if (found.isEmpty()) throw FsException("服务器没有可浏览的共享，请在服务器设置中填写共享名")
                return@io found.map { FileEntry("/${it.name}", it.name, true, 0, 0) }
            }
            return@io shareRoots()
        }
        val sh = share(l.share)
        val dir = sh.openDirectory(
            l.path, EnumSet.of(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES),
            null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null,
        )
        val out = ArrayList<FileEntry>()
        val chunk = ArrayList<FileEntry>()
        dir.use {
            val it = dir.iterator(FileIdBothDirectoryInformation::class.java)
            while (it.hasNext()) {
                val info = it.next()
                val name = info.fileName
                if (name == "." || name == "..") continue
                val isDir = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                val e = FileEntry(
                    path = VPath.join(path, name), name = name, isDirectory = isDir,
                    size = if (isDir) 0 else info.endOfFile,
                    lastModified = info.lastWriteTime.toEpochMillis(),
                    isLink = info.fileAttributes and FileAttributes.FILE_ATTRIBUTE_REPARSE_POINT.value != 0L,
                )
                out.add(e)
                if (onChunk != null) {
                    chunk.add(e)
                    if (chunk.size >= 300) { onChunk(ArrayList(chunk)); chunk.clear() }
                }
            }
        }
        if (onChunk != null && chunk.isNotEmpty()) onChunk(chunk)
        out
    }

    override fun stat(path: String): FileEntry? {
        val l = loc(path) ?: return FileEntry(VPath.ROOT, "", true, 0, 0)
        if (l.path.isEmpty()) return FileEntry(VPath.normalize(path), l.share, true, 0, 0)
        return try {
            io("读取属性 $path") {
                val info = share(l.share).getFileInformation(l.path)
                val isDir = info.standardInformation.isDirectory
                FileEntry(
                    VPath.normalize(path), VPath.name(path), isDir,
                    if (isDir) 0 else info.standardInformation.endOfFile,
                    info.basicInformation.lastWriteTime.toEpochMillis(),
                )
            }
        } catch (e: FsException) {
            val st = (e.cause as? SMBApiException)?.status
            if (st == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || st == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
                st == NtStatus.STATUS_NO_SUCH_FILE) null else throw e
        }
    }

    private fun requireFile(path: String): Loc {
        val l = loc(path) ?: throw FsException("无效路径: $path")
        if (l.path.isEmpty()) throw FsException("无效路径: $path")
        return l
    }

    override fun openRead(path: String, offset: Long): InputStream = io("打开 $path") {
        val l = requireFile(path)
        val sh = share(l.share)
        val f = sh.openFile(
            l.path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        val maxRead = sh.treeConnect.session.connection.negotiatedProtocol.maxReadSize
        SmbInput(f, offset, maxRead.coerceIn(64 * 1024, 1024 * 1024)) { _ -> reopenFile(path, write = false) }
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream = io("写入 $path") {
        val l = requireFile(path)
        val sh = share(l.share)
        val f = sh.openFile(
            l.path, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            if (offset > 0) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        if (offset > 0) f.setLength(offset)
        val maxWrite = sh.treeConnect.session.connection.negotiatedProtocol.maxWriteSize
        SmbOutput(f, offset, maxWrite.coerceIn(64 * 1024, 1024 * 1024)) { reopenFile(path, write = true) }
    }

    override fun mkdirs(path: String) = io("创建目录 $path") {
        val l = requireFile(path)
        val sh = share(l.share)
        var cur = ""
        for (part in l.path.split('\\')) {
            cur = if (cur.isEmpty()) part else "$cur\\$part"
            if (!sh.folderExists(cur)) sh.mkdir(cur)
        }
    }

    override fun delete(path: String, recursive: Boolean) = io("删除 $path") {
        val l = requireFile(path)
        val sh = share(l.share)
        val info = try {
            sh.getFileInformation(l.path)
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND ||
                e.status == NtStatus.STATUS_DELETE_PENDING) return@io
            throw e
        }
        if (info.standardInformation.isDirectory) {
            sh.rmdir(l.path, recursive)
            return@io
        }
        try {
            sh.rm(l.path)
        } catch (e: SMBApiException) {
            // Read-only attribute (common on files copied from CDs/phones to Windows): clear it and retry once.
            if (e.status != NtStatus.STATUS_CANNOT_DELETE && e.status != NtStatus.STATUS_ACCESS_DENIED) throw e
            val attrs = info.basicInformation.fileAttributes
            if (attrs and FileAttributes.FILE_ATTRIBUTE_READONLY.value == 0L) throw e
            sh.openFile(
                l.path, EnumSet.of(AccessMask.FILE_WRITE_ATTRIBUTES, AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { f ->
                val b = info.basicInformation
                f.setFileInformation(
                    com.hierynomus.msfscc.fileinformation.FileBasicInformation(
                        b.creationTime, b.lastAccessTime, b.lastWriteTime, b.changeTime,
                        (attrs and FileAttributes.FILE_ATTRIBUTE_READONLY.value.inv()).let { if (it == 0L) FileAttributes.FILE_ATTRIBUTE_NORMAL.value else it },
                    )
                )
            }
            sh.rm(l.path)
        }
    }

    override fun rename(from: String, to: String) = io("移动 $from") {
        val a = requireFile(from)
        val b = requireFile(to)
        if (a.share != b.share) throw FsException("不能跨共享移动")
        val sh = share(a.share)
        val entry = sh.open(
            a.path, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, null,
        )
        entry.use { it.rename(b.path, false) }
    }

    override fun serverCopy(from: String, to: String): Boolean {
        val a = loc(from) ?: return false
        val b = loc(to) ?: return false
        if (a.share != b.share || a.path.isEmpty() || b.path.isEmpty()) return false
        return try {
            val sh = share(a.share)
            if (sh.folderExists(a.path)) return false
            sh.openFile(a.path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, null).use { src ->
                sh.openFile(b.path, EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null).use { dst ->
                    src.remoteCopyTo(dst)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    override fun reset() {
        synchronized(lock) {
            diskShares.clear()
            runCatching { session?.connection?.close(true) }
            session = null
        }
    }

    override fun close() {
        reset()
        runCatching { connector.close() }
    }

    /** Re-opens [path] on a fresh connection (after the old one died mid-stream). */
    private fun reopenFile(path: String, write: Boolean): File {
        reset()
        val l = requireFile(path)
        return share(l.share).openFile(
            l.path,
            if (write) EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ) else EnumSet.of(AccessMask.GENERIC_READ),
            null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
    }

    /** Tears the connection down so blocked requests fail at once (only for dedicated instances). */
    private fun abortConnection() {
        if (dedicated) reset()
    }

    /**
     * Sequential reader at an explicit offset. If the connection drops mid-file it reconnects and
     * continues at the same offset (up to [MAX_REOPEN] times without progress) instead of failing
     * the whole transfer attempt.
     */
    private inner class SmbInput(
        @Volatile private var f: File,
        private var pos: Long,
        private val chunk: Int,
        private val reopen: (Long) -> File,
    ) : InputStream(), Abortable {
        @Volatile private var aborted = false
        private var reopens = 0
        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                try {
                    val n = f.read(b, pos, off, minOf(len, chunk))
                    if (n <= 0) return -1
                    pos += n
                    reopens = 0
                    return n
                } catch (e: Exception) {
                    if (aborted || e is SMBApiException || !isConnectionError(e) || reopens >= MAX_REOPEN) {
                        throw FsException("读取: ${ErrorText.describe(e)}", e)
                    }
                    reopens++
                    f = try { reopen(pos) } catch (r: Exception) { throw FsException("重新连接失败: ${ErrorText.describe(r)}", r) }
                }
            }
        }
        override fun close() { runCatching { f.close() } }
        override fun abort() { aborted = true; runCatching { f.closeNoWait() }; abortConnection() }
    }

    private inner class SmbOutput(
        @Volatile private var f: File,
        private var pos: Long,
        private val chunk: Int,
        private val reopen: () -> File,
    ) : OutputStream(), Abortable {
        @Volatile private var aborted = false
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            var reopens = 0
            while (left > 0) {
                val n = try {
                    f.write(b, pos, o, minOf(left, chunk)).toInt()
                } catch (e: Exception) {
                    if (aborted || e is SMBApiException || !isConnectionError(e) || reopens >= MAX_REOPEN) {
                        throw FsException("写入: ${ErrorText.describe(e)}", e)
                    }
                    reopens++
                    f = reopen()
                    continue
                }
                if (n <= 0) throw FsException("SMB 写入返回 0")
                pos += n; o += n; left -= n; reopens = 0
            }
        }
        override fun flush() { runCatching { f.flush() } }
        override fun close() { f.close() }
        override fun abort() { aborted = true; runCatching { f.closeNoWait() }; abortConnection() }
    }

    companion object {
        private const val MAX_REOPEN = 3

        /** True when [e] means the TCP connection / session is unusable (as opposed to an SMB status error). */
        fun isConnectionError(e: Throwable): Boolean = ErrorText.chain(e).any {
            it is com.hierynomus.protocol.transport.TransportException ||
                it is java.net.SocketException || it is java.io.EOFException ||
                it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException ||
                (it.message ?: "").contains("already been closed", true) ||
                (it.message ?: "").contains("not connected", true)
        }
    }
}
