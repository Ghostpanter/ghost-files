package com.ghostpanter.files.core

import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * FTP / explicit FTPS via Apache Commons Net.
 *
 * An FTP control connection can only run one command/transfer at a time, so
 * this keeps a small pool of logged-in clients: listing never waits behind a
 * running transfer, a broken client is discarded instead of reused, and an
 * aborted transfer drops its connection rather than blocking on
 * completePendingCommand() (the classic "copy hangs forever" bug).
 */
class FtpFs(
    private val config: ServerConfig,
    private val timeouts: NetTimeouts = NetTimeouts(),
    maxConnections: Int = 4,
) : VirtualFs {

    override val id = config.fsId
    override val displayName = config.name
    override val capabilities = setOf(
        Capability.RESUME_READ, Capability.RESUME_WRITE, Capability.RENAME_MOVE,
        Capability.CHMOD, Capability.REMOTE,
    )
    override val homePath: String = VPath.normalize(config.path.ifEmpty { "/" })

    private val permits = Semaphore(maxConnections, true)
    private val idle = LinkedBlockingDeque<Pooled>()
    @Volatile private var mlsd: Boolean? = null

    private class Pooled(val client: FTPClient, var lastUsed: Long = System.currentTimeMillis())

    private fun connect(): FTPClient {
        val c = if (config.protocol == Protocol.FTPS) FTPSClient(false) else FTPClient()
        c.controlEncoding = config.encoding.ifEmpty { "UTF-8" }
        c.connectTimeout = timeouts.connectMs
        c.defaultTimeout = timeouts.readMs
        c.setDataTimeout(Duration.ofMillis(timeouts.readMs.toLong()))
        c.setControlKeepAliveTimeout(Duration.ofSeconds(timeouts.keepAliveSec.toLong()))
        c.setControlKeepAliveReplyTimeout(Duration.ofSeconds(10))
        c.bufferSize = 256 * 1024
        try {
            c.connect(config.host, config.effectivePort)
            c.soTimeout = timeouts.readMs
            if (!FTPReply.isPositiveCompletion(c.replyCode)) throw FsException(tr("服务器拒绝连接: ${c.replyString.trim()}", "Server refused connection: ${c.replyString.trim()}"))
            if (c is FTPSClient) {
                c.execPBSZ(0)
                c.execPROT("P")
            }
            val user = if (config.anonymous || config.username.isEmpty()) "anonymous" else config.username
            val pass = if (config.anonymous || config.username.isEmpty()) "ghost@files" else config.password
            if (!c.login(user, pass)) throw FsException(tr("登录失败: ${c.replyString.trim()}", "Login failed: ${c.replyString.trim()}"))
            runCatching { c.sendCommand("OPTS UTF8 ON") }
            c.setFileType(FTP.BINARY_FILE_TYPE)
            if (config.passiveMode) c.enterLocalPassiveMode() else c.enterLocalActiveMode()
            if (mlsd == null) mlsd = runCatching { c.hasFeature("MLSD") }.getOrDefault(false)
            return c
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            if (e is FsException) throw e
            throw FsException(tr("连接 FTP 失败: ${e.message}", "FTP connection failed: ${e.message}"), e)
        }
    }

    private fun borrow(): FTPClient {
        if (!permits.tryAcquire(timeouts.readMs.toLong() * 2, TimeUnit.MILLISECONDS)) {
            throw FsException(tr("FTP 连接池繁忙，请稍后重试", "FTP connection pool busy, try again later"))
        }
        try {
            while (true) {
                val p = idle.pollFirst() ?: break
                if (!p.client.isConnected) continue
                // Validate connections that sat idle long enough for the server to drop them.
                if (System.currentTimeMillis() - p.lastUsed > 20_000) {
                    val alive = runCatching { p.client.sendNoOp() }.getOrDefault(false)
                    if (!alive) { discardQuietly(p.client); continue }
                }
                return p.client
            }
            return connect()
        } catch (e: Throwable) {
            permits.release()
            throw e
        }
    }

    private fun release(c: FTPClient, healthy: Boolean) {
        if (healthy && c.isConnected) idle.offerFirst(Pooled(c)) else discardQuietly(c)
        permits.release()
    }

    private fun discardQuietly(c: FTPClient) {
        Thread { runCatching { c.abort() }; runCatching { c.disconnect() } }.apply { isDaemon = true }.start()
    }

    private inline fun <T> withClient(what: String, block: (FTPClient) -> T): T {
        val c = borrow()
        var healthy = true
        try {
            return block(c)
        } catch (e: FsException) {
            throw e
        } catch (e: IOException) {
            healthy = false
            throw FsException("$what: ${e.message}", e)
        } finally {
            release(c, healthy)
        }
    }

    private fun check(c: FTPClient, ok: Boolean, what: String) {
        if (!ok) throw FsException("$what: ${c.replyString?.trim()}")
    }

    private fun toEntry(dir: String, f: FTPFile): FileEntry? {
        val name = f.name?.substringAfterLast('/') ?: return null
        if (name == "." || name == "..") return null
        val perms = if (f.hasPermission(FTPFile.USER_ACCESS, FTPFile.READ_PERMISSION) || f.rawListing?.firstOrNull() in listOf('d', '-', 'l')) {
            buildString {
                append(if (f.isDirectory) 'd' else if (f.isSymbolicLink) 'l' else '-')
                for (a in intArrayOf(FTPFile.USER_ACCESS, FTPFile.GROUP_ACCESS, FTPFile.WORLD_ACCESS)) {
                    append(if (f.hasPermission(a, FTPFile.READ_PERMISSION)) 'r' else '-')
                    append(if (f.hasPermission(a, FTPFile.WRITE_PERMISSION)) 'w' else '-')
                    append(if (f.hasPermission(a, FTPFile.EXECUTE_PERMISSION)) 'x' else '-')
                }
            }
        } else null
        return FileEntry(
            path = VPath.join(dir, name), name = name,
            isDirectory = f.isDirectory || (f.isSymbolicLink && f.link?.endsWith("/") == true),
            size = if (f.isDirectory) 0 else f.size.coerceAtLeast(0),
            lastModified = f.timestamp?.timeInMillis ?: 0,
            isLink = f.isSymbolicLink, permissions = perms, owner = f.user, group = f.group,
        )
    }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> = withClient("列出 $path") { c ->
        val dir = VPath.normalize(path)
        val files: Array<FTPFile> = if (mlsd == true) c.mlistDir(dir) else c.listFiles(dir)
        if (files.isEmpty() && !FTPReply.isPositiveCompletion(c.replyCode)) {
            throw FsException(tr("列出 $path 失败: ${c.replyString?.trim()}", "List $path failed: ${c.replyString?.trim()}"))
        }
        val out = files.mapNotNull { toEntry(dir, it) }
        onChunk?.invoke(out)
        out
    }

    override fun stat(path: String): FileEntry? {
        val p = VPath.normalize(path)
        if (p == VPath.ROOT) return FileEntry(p, "", true, 0, 0)
        return withClient("读取属性 $path") { c ->
            if (mlsd == true) {
                val f = c.mlistFile(p)
                if (f != null) return@withClient toEntry(VPath.parent(p), f.also { if (it.name.contains('/')) it.name = VPath.name(p) })
                if (c.replyCode == 550 || c.replyCode == 501) return@withClient null
            }
            val parent = VPath.parent(p)
            val name = VPath.name(p)
            val files = if (mlsd == true) c.mlistDir(parent) else c.listFiles(parent)
            files.firstOrNull { it.name?.substringAfterLast('/') == name }?.let { toEntry(parent, it) }
        }
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val c = borrow()
        try {
            if (offset > 0) c.restartOffset = offset
            val raw = c.retrieveFileStream(VPath.normalize(path))
            if (raw == null) {
                val msg = c.replyString?.trim()
                release(c, c.isConnected)
                throw FsException(tr("打开 $path 失败: $msg", "Open $path failed: $msg"))
            }
            return object : FilterInputStream(raw) {
                private var eof = false
                private var done = false
                override fun read(): Int = super.read().also { if (it < 0) eof = true }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it < 0) eof = true }
                override fun close() {
                    if (done) return
                    done = true
                    runCatching { super.close() }
                    if (eof) {
                        val ok = runCatching { c.completePendingCommand() }.getOrDefault(false)
                        release(c, ok)
                    } else {
                        // Aborted mid-transfer: never wait on the server, just drop the connection.
                        release(c, false)
                    }
                }
            }
        } catch (e: FsException) {
            throw e
        } catch (e: IOException) {
            release(c, false)
            throw FsException(tr("打开 $path 失败: ${e.message}", "Open $path failed: ${e.message}"), e)
        }
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream {
        val c = borrow()
        try {
            val p = VPath.normalize(path)
            val raw = if (offset > 0) c.appendFileStream(p) else c.storeFileStream(p)
            if (raw == null) {
                val msg = c.replyString?.trim()
                release(c, c.isConnected)
                throw FsException(tr("写入 $path 失败: $msg", "Write $path failed: $msg"))
            }
            return object : FilterOutputStream(raw) {
                private var done = false
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() {
                    if (done) return
                    done = true
                    var ok = false
                    try {
                        out.close()
                        ok = c.completePendingCommand()
                        if (!ok) throw FsException(tr("写入 $path 未完成: ${c.replyString?.trim()}", "Write $path incomplete: ${c.replyString?.trim()}"))
                    } catch (e: IOException) {
                        if (e is FsException) throw e
                        throw FsException(tr("写入 $path 失败: ${e.message}", "Write $path failed: ${e.message}"), e)
                    } finally {
                        release(c, ok)
                    }
                }
            }
        } catch (e: FsException) {
            throw e
        } catch (e: IOException) {
            release(c, false)
            throw FsException(tr("写入 $path 失败: ${e.message}", "Write $path failed: ${e.message}"), e)
        }
    }

    override fun mkdirs(path: String) = withClient("创建目录 $path") { c ->
        var cur = ""
        for (part in VPath.normalize(path).trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = "$cur/$part"
            if (!c.changeWorkingDirectory(cur)) check(c, c.makeDirectory(cur), tr("创建目录 $cur", "Create directory $cur"))
        }
        c.changeWorkingDirectory("/")
        Unit
    }

    override fun delete(path: String, recursive: Boolean) {
        val e = stat(path) ?: return
        if (!e.isDirectory) {
            withClient("删除 $path") { c -> check(c, c.deleteFile(e.path), tr("删除 $path", "Delete $path")) }
            return
        }
        if (recursive) for (child in list(e.path)) delete(child.path, true)
        withClient("删除 $path") { c -> check(c, c.removeDirectory(e.path), "删除目录 $path") }
    }

    override fun rename(from: String, to: String) = withClient("移动 $from") { c ->
        check(c, c.rename(VPath.normalize(from), VPath.normalize(to)), tr("移动 $from", "Move $from"))
    }

    override fun chmod(path: String, mode: String, recursive: Boolean) = withClient("修改权限 $path") { c ->
        require(mode.matches(Regex("[0-7]{3,4}"))) { tr("FTP 仅支持数字权限", "FTP supports numeric modes only") }
        check(c, c.sendSiteCommand("CHMOD $mode ${VPath.normalize(path)}"), tr("修改权限", "chmod"))
    }

    override fun setLastModified(path: String, millis: Long) {
        if (millis <= 0) return
        runCatching {
            withClient("设置时间") { c ->
                val fmt = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                c.setModificationTime(VPath.normalize(path), fmt.format(Date(millis)))
            }
        }
    }

    override fun reset() {
        while (true) {
            val p = idle.pollFirst() ?: break
            discardQuietly(p.client)
        }
    }

    override fun close() = reset()
}
