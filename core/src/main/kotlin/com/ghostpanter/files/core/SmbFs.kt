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
import com.hierynomus.smbj.share.GfSmbAccess
import com.hierynomus.mssmb2.messages.SMB2ReadResponse
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
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

    // Reads have their own short stall detection + reconnect (SmbInput) and report recovery as
    // activity; this only has to cover one metadata request / reconnect handshake.
    override val stallHintMs: Long get() = timeouts.smbRequestMs + 15_000

    @Volatile private var session: Session? = null
    private val diskShares = ConcurrentHashMap<String, DiskShare>()
    private val lock = Any()

    private fun session(): Session = synchronized(lock) {
        lastUse = System.currentTimeMillis()
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
        val sh = session().connectShare(name) as? DiskShare ?: throw FsException(tr("$name 不是磁盘共享", "$name is not a disk share"))
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
        NtStatus.STATUS_ACCESS_DENIED -> tr("拒绝访问", "Access denied")
        NtStatus.STATUS_LOGON_FAILURE -> tr("用户名或密码错误", "Wrong username or password")
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> tr("不存在", "Not found")
        NtStatus.STATUS_OBJECT_NAME_COLLISION -> tr("已存在", "Already exists")
        NtStatus.STATUS_BAD_NETWORK_NAME -> tr("共享名不存在", "Share not found")
        NtStatus.STATUS_SHARING_VIOLATION -> tr("文件正被其他程序占用", "File is in use by another program")
        NtStatus.STATUS_CANNOT_DELETE -> tr("文件为只读或正在使用，无法删除", "File is read-only or in use; cannot delete")
        NtStatus.STATUS_DISK_FULL -> tr("空间不足", "Disk full")
        NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> tr("目录非空", "Directory not empty")
        else -> s.name
    }

    private fun shareRoots() = shares.map { FileEntry("/$it", it, true, 0, 0) }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> = io(tr("列出 $path", "List $path")) {
        val l = loc(path)
        if (l == null) {
            if (shares.isEmpty()) {
                // No shares configured: enumerate them (SRVSVC NetShareEnum) like Explorer does.
                val found = com.ghostpanter.files.core.lan.SmbShares.userVisible(
                    com.ghostpanter.files.core.lan.SmbShares.enumerate(session())
                )
                if (found.isEmpty()) throw FsException(tr("服务器没有可浏览的共享，请在服务器设置中填写共享名", "Server has no browsable shares; enter a share name in server settings"))
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
            io(tr("读取属性 $path", "Stat $path")) {
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
        val l = loc(path) ?: throw FsException(tr("无效路径: $path", "Invalid path: $path"))
        if (l.path.isEmpty()) throw FsException(tr("无效路径: $path", "Invalid path: $path"))
        return l
    }

    override fun openRead(path: String, offset: Long): InputStream = io(tr("打开 $path", "Open $path")) {
        val l = requireFile(path)
        val sh = share(l.share)
        val f = sh.openFile(
            l.path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        val maxRead = sh.treeConnect.session.connection.negotiatedProtocol.maxReadSize
        val size = runCatching { f.getFileInformation(FileStandardInformation::class.java).endOfFile }.getOrDefault(Long.MAX_VALUE)
        SmbInput(f, path, offset, size, minOf(readChunk, maxRead).coerceIn(MIN_CHUNK, MAX_CHUNK))
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream = io(tr("写入 $path", "Write $path")) {
        val l = requireFile(path)
        val sh = share(l.share)
        val f = sh.openFile(
            l.path, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
            if (offset > 0) SMB2CreateDisposition.FILE_OPEN_IF else SMB2CreateDisposition.FILE_OVERWRITE_IF,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        if (offset > 0) f.setLength(offset)
        val maxWrite = sh.treeConnect.session.connection.negotiatedProtocol.maxWriteSize
        SmbOutput(f, path, offset, maxWrite.coerceIn(MIN_CHUNK, MAX_CHUNK))
    }

    override fun mkdirs(path: String) = io(tr("创建目录 $path", "Create directory $path")) {
        val l = requireFile(path)
        val sh = share(l.share)
        var cur = ""
        for (part in l.path.split('\\')) {
            cur = if (cur.isEmpty()) part else "$cur\\$part"
            if (!sh.folderExists(cur)) sh.mkdir(cur)
        }
    }

    override fun delete(path: String, recursive: Boolean) = io(tr("删除 $path", "Delete $path")) {
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

    override fun rename(from: String, to: String) = io(tr("移动 $from", "Move $from")) {
        val a = requireFile(from)
        val b = requireFile(to)
        if (a.share != b.share) throw FsException(tr("不能跨共享移动", "Cannot move across shares"))
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
        if (serverCopyUnsupported) return false // learned on this server: go straight to streaming
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
            // Not every server implements FSCTL_SRV_COPYCHUNK (old Samba, ksmbd builds, routers):
            // remember it and silently fall back to a normal stream copy.
            serverCopyUnsupported = true
            if (isConnectionError(e)) reset()
            false
        }
    }

    @Volatile private var serverCopyUnsupported = false

    override fun reset() {
        val c = synchronized(lock) {
            diskShares.clear()
            val c = session?.connection
            session = null
            c
        } ?: return
        killConnection(c)
    }

    /**
     * smbj's close(force = true) stops the reader thread WITHOUT failing outstanding requests, so
     * anything still waiting on that connection would sit out its full timeout. Fail them too.
     */
    private fun killConnection(c: com.hierynomus.smbj.connection.Connection) {
        runCatching { c.close(true) }
        Thread({ runCatching { c.handleError(com.hierynomus.protocol.transport.TransportException(tr("连接已丢弃（重连）", "Connection dropped (reconnecting)"))) } }, "smb-kill")
            .apply { isDaemon = true }.start()
    }

    override fun close() {
        echo.shutdownNow()
        reset()
        runCatching { connector.close() }
    }

    /** Re-opens [path] on a fresh connection (after the old one died mid-stream). */
    /** Re-opens [path] on a fresh connection (after the old one died or stalled mid-stream). */
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

    private class Aborted : java.io.IOException(tr("已中止", "Aborted"))

    /**
     * Shared recovery policy of the read/write streams: on a connection-level failure (timeout,
     * reset, dropped/black-holed connection, credit starvation) reconnect with backoff and continue
     * at the same offset, for up to [recoveryBudgetMs] without progress. Each attempt is reported
     * to the engine (counts as "自动重连", keeps the watchdog quiet) - it is not a job retry.
     */
    private inner class Recovery {
        @Volatile var aborted = false
        @Volatile var onRecovery: ((Throwable?, Int) -> Unit)? = null
        private var attempts = 0
        private var since = 0L

        fun recover(what: String, e: Throwable, reopen: () -> Unit) {
            if (aborted) throw FsException(tr("$what: 已中止", "$what: aborted"), e)
            if (e is SMBApiException && !isSessionGone(e.status)) throw FsException("$what: ${statusText(e.status)}", e)
            if (e is FsException || !isConnectionError(e)) throw (e as? FsException ?: FsException("$what: ${ErrorText.describe(e)}", e))
            val now = System.currentTimeMillis()
            if (attempts == 0) since = now
            if (now - since > recoveryBudgetMs) {
                throw FsException(tr("$what: ${ErrorText.describe(e)}（已自动重连 $attempts 次仍失败）", "$what: ${ErrorText.describe(e)} (still failing after $attempts reconnects)"), e)
            }
            attempts++
            if (System.getenv("GF_SMB_DEBUG") != null) System.err.println("GFDBG ${System.currentTimeMillis()} recover#$attempts $what: ${ErrorText.describe(e)}")
            runCatching { onRecovery?.invoke(e, attempts) }
            if (attempts > 1) sleepAbortable(minOf(500L shl (attempts - 2).coerceAtMost(4), 8_000L))
            while (true) {
                if (aborted) throw FsException(tr("$what: 已中止", "$what: aborted"), e)
                try { reopen(); break } catch (r: Exception) {
                    if (!isConnectionError(r) || System.currentTimeMillis() - since > recoveryBudgetMs) {
                        throw FsException(tr("重新连接失败: ${ErrorText.describe(r)}", "Reconnect failed: ${ErrorText.describe(r)}"), r)
                    }
                    runCatching { onRecovery?.invoke(r, attempts) }
                    sleepAbortable(2_000)
                }
            }
            if (System.getenv("GF_SMB_DEBUG") != null) System.err.println("GFDBG ${System.currentTimeMillis()} reopened#$attempts")
            runCatching { onRecovery?.invoke(null, attempts) }
        }

        fun progressed() { attempts = 0 }

        private fun sleepAbortable(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (!aborted && System.currentTimeMillis() < end) Thread.sleep(50)
        }
    }

    /**
     * Sequential reader with up to two reads in flight. Never requests at/after the known end of
     * file (some servers never answer such a read), detects a stalled read after
     * [NetTimeouts.smbReadStallMs] instead of waiting the full request timeout, and on stall /
     * dropped connection reconnects and continues at the same offset with a smaller read size.
     */
    private inner class SmbInput(
        @Volatile private var f: File,
        private val path: String,
        private var pos: Long,
        private var size: Long,
        private var chunk: Int,
    ) : InputStream(), Abortable, RecoveringStream {
        private val rec = Recovery()
        override var onRecovery by rec::onRecovery
        private inner class Req(val offset: Long, val len: Int, val fut: java.util.concurrent.Future<SMB2ReadResponse>)
        private val inflight = ArrayDeque<Req>()
        private var depth = if (chunk >= 512 * 1024) 2 else 1
        private var cleanBytes = 0L
        private var nextReq = pos
        private var buf = ByteArray(0)
        private var bufPos = 0
        private var bufLen = 0

        override fun read(): Int {
            val b = ByteArray(1)
            return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (bufLen == 0) {
                if (pos >= size) return -1
                try {
                    if (!fill()) return -1
                    rec.progressed()
                } catch (e: Aborted) {
                    throw FsException(tr("读取: 已中止", "Read: aborted"), e)
                } catch (e: Exception) {
                    dropInflight()
                    rec.recover(tr("读取", "Read"), e) {
                        // Adaptive: a stall halves the read size for this server (down to 64 KB) and
                        // drops read-ahead; clean reads grow it back (see fill()).
                        chunk = (chunk / 2).coerceAtLeast(MIN_CHUNK)
                        readChunk = chunk
                        cleanBytes = 0
                        depth = 1
                        f = reopenFile(path, write = false)
                        runCatching { size = f.getFileInformation(FileStandardInformation::class.java).endOfFile }
                    }
                }
            }
            val n = minOf(len, bufLen)
            System.arraycopy(buf, bufPos, b, off, n)
            bufPos += n; bufLen -= n; pos += n
            return n
        }

        /** Loads the next response into [buf]; false at end of file. */
        private fun fill(): Boolean {
            lastUse = System.currentTimeMillis()
            while (inflight.size < depth && nextReq < size) {
                val l = minOf(chunk.toLong(), size - nextReq).toInt()
                inflight.addLast(Req(nextReq, l, GfSmbAccess.readAsync(f, nextReq, l)))
                nextReq += l
            }
            val r = inflight.removeFirstOrNull() ?: return false
            val resp = await(r.fut)
            if (resp.header.statusCode == NtStatus.STATUS_END_OF_FILE.value) { size = pos; dropInflight(); return false }
            if (!NtStatus.isSuccess(resp.header.statusCode)) throw SMBApiException(resp.header, "Read failed for $path")
            val data = resp.data
            if (data.isEmpty()) { size = pos; dropInflight(); return false }
            if (data.size < r.len) { dropInflight(); nextReq = r.offset + data.size } // short read: re-plan
            buf = data; bufPos = 0; bufLen = data.size
            cleanBytes += data.size
            if (cleanBytes >= 64L * 1024 * 1024 && (chunk < MAX_CHUNK || depth < 2)) {
                chunk = (chunk * 2).coerceAtMost(MAX_CHUNK); readChunk = chunk; depth = 2; cleanBytes = 0
            }
            return true
        }

        /** Waits for a read without blocking abort; a read silent for smbReadStallMs is a stall. */
        private fun <T> await(fut: java.util.concurrent.Future<T>): T {
            val deadline = System.currentTimeMillis() + timeouts.smbReadStallMs
            while (true) {
                if (rec.aborted) throw Aborted()
                try {
                    return fut.get(200, java.util.concurrent.TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.ExecutionException) {
                    // smbj reports the expiry of OUR 200 ms wait slice as ExecutionException(...
                    // TimeoutException("Timeout expired")); anything else is a delivered failure
                    // (e.g. the reader thread died) and must surface at once. (isDone() is false
                    // for failed promises in smbj, so it can't be used to tell them apart.)
                    if (!isWaitSliceExpiry(e)) throw e
                } catch (e: java.util.concurrent.TimeoutException) {
                    // plain java Future semantics: slice expired
                }
                if (System.currentTimeMillis() > deadline) {
                    throw java.util.concurrent.TimeoutException(tr("SMB 读取 ${timeouts.smbReadStallMs / 1000} 秒无响应", "SMB read stalled for ${timeouts.smbReadStallMs / 1000}s"))
                }
            }
        }

        private fun dropInflight() {
            inflight.clear()
            nextReq = pos + bufLen
        }

        override fun close() { dropInflight(); runCatching { f.close() } }
        override fun abort() { rec.aborted = true; runCatching { f.closeNoWait() }; abortConnection() }
    }

    private inner class SmbOutput(
        @Volatile private var f: File,
        private val path: String,
        private var pos: Long,
        private val chunk: Int,
    ) : OutputStream(), Abortable, RecoveringStream {
        private val rec = Recovery()
        override var onRecovery by rec::onRecovery
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var left = len
            while (left > 0) {
                if (rec.aborted) throw FsException(tr("写入: 已中止", "Write: aborted"))
                val n = try {
                    f.write(b, pos, o, minOf(left, chunk)).toInt()
                } catch (e: Exception) {
                    rec.recover(tr("写入", "Write"), e) { f = reopenFile(path, write = true) }
                    continue
                }
                if (n <= 0) throw FsException(tr("SMB 写入返回 0", "SMB write returned 0"))
                pos += n; o += n; left -= n
                rec.progressed()
            }
        }
        override fun flush() { runCatching { f.flush() } }
        override fun close() { f.close() }
        override fun abort() { rec.aborted = true; runCatching { f.closeNoWait() }; abortConnection() }
    }

    /** How long one stall may be ridden out in-stream (10 min by default) before the job retries. */
    private val recoveryBudgetMs: Long get() = timeouts.readMs * 20L

    /** Current read size for this server; starts at 1 MB, adapted by SmbInput. */
    @Volatile private var readChunk = MAX_CHUNK

    @Volatile private var lastUse = System.currentTimeMillis()
    private val echoMs: Long get() = maxOf(timeouts.readMs.toLong(), 5_000L)
    private val echo = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "smb-echo").apply { isDaemon = true }
    }.also { ex ->
        ex.scheduleWithFixedDelay({ runCatching { echoIfIdle() } }, echoMs, echoMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * SMB2 ECHO on an idle connection: keeps NAT / router / server idle timers from silently
     * dropping it, and detects a dead connection before the next real request has to.
     */
    private fun echoIfIdle() {
        val c = session?.connection ?: return
        if (System.currentTimeMillis() - lastUse < echoMs) return
        if (!c.isConnected) { reset(); return }
        val f = c.send<com.hierynomus.mssmb2.SMB2Packet>(com.hierynomus.mssmb2.messages.SMB2Echo(c.negotiatedProtocol.dialect))
        try {
            f.get(timeouts.smbReadStallMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            if (session?.connection === c) reset() // no answer: drop it now, the next call reconnects
        }
    }

    companion object {
        private const val MIN_CHUNK = 64 * 1024
        private const val MAX_CHUNK = 1024 * 1024

        internal fun isWaitSliceExpiry(e: Throwable): Boolean {
            val r = ErrorText.root(e)
            return r is java.util.concurrent.TimeoutException && r.message == "Timeout expired" &&
                ErrorText.chain(e).none { it is com.hierynomus.protocol.transport.TransportException && it.cause !is java.util.concurrent.ExecutionException && it.cause != null && it.cause !is com.hierynomus.smbj.common.SMBRuntimeException }
        }

        /** True when [e] means the TCP connection / session is unusable (as opposed to an SMB status error). */
        fun isConnectionError(e: Throwable): Boolean = e !is SMBApiException && ErrorText.chain(e).any {
            it is com.hierynomus.protocol.transport.TransportException ||
                (it.message ?: "").contains("credits", true) ||
                it is java.net.SocketException || it is java.io.EOFException ||
                it is java.net.SocketTimeoutException || it is java.util.concurrent.TimeoutException ||
                (it.message ?: "").contains("already been closed", true) ||
                (it.message ?: "").contains("not connected", true)
        }
    }
}
