package com.ghostpanter.files.core

import com.ghostpanter.files.core.net.LanSocketBinder
import com.ghostpanter.files.core.net.LanVpnPolicy
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.Config
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.BufferedOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * SFTP via sshj. One SSH connection per server with SSH-level keepalive;
 * SFTP requests are pipelined (read-ahead / unconfirmed writes) so LAN
 * throughput is not bound by round-trip latency. Host keys are pinned on
 * first use and a changed key is refused.
 */
class SftpFs(
    private val config: ServerConfig,
    private val hostKeys: HostKeyStore,
    private val timeouts: NetTimeouts = NetTimeouts(),
    private val sshConfig: () -> Config,
) : VirtualFs {

    override val id = config.fsId
    override val displayName = config.name
    override val capabilities = setOf(
        Capability.RESUME_READ, Capability.RESUME_WRITE, Capability.SERVER_COPY,
        Capability.RENAME_MOVE, Capability.CHMOD, Capability.REMOTE,
    )
    override val homePath: String = VPath.normalize(config.path.ifEmpty { "/" })

    private val lock = Any()
    @Volatile private var ssh: SSHClient? = null
    @Volatile private var sftp: SFTPClient? = null
    @Volatile private var jumpSsh: SSHClient? = null

    private val hostKey = "${config.host}:${config.effectivePort}"

    private val verifier = object : HostKeyVerifier {
        override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
            val fp = fingerprint(key)
            val known = hostKeys.get(hostKey)
            if (known == null) { hostKeys.put(hostKey, fp); return true }
            return known == fp
        }
        override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
    }

    private fun client(): SFTPClient = synchronized(lock) {
        val s = ssh
        val f = sftp
        if (s != null && f != null && s.isConnected && s.isAuthenticated) return f
        closeQuietly()
        val cfg = sshConfig().also { it.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
        val c = SSHClient(cfg)
        var jump: SSHClient? = null
        try {
            c.addHostKeyVerifier(verifier)
            c.connectTimeout = timeouts.connectMs
            c.timeout = timeouts.readMs
            c.socketFactory = LanSocketBinder.Factory(timeouts.connectMs)
            if (config.jumpHost.isNotBlank()) {
                val jcfg = sshConfig().also { it.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE }
                val j = SSHClient(jcfg)
                jump = j
                j.addHostKeyVerifier(object : HostKeyVerifier {
                    private val key = "${config.jumpHost}:${if (config.jumpPort > 0) config.jumpPort else 22}"
                    override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                        val fp = fingerprint(key)
                        val known = hostKeys.get(this.key)
                        if (known == null) { hostKeys.put(this.key, fp); return true }
                        return known == fp
                    }
                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
                })
                j.connectTimeout = timeouts.connectMs
                j.timeout = timeouts.readMs
                j.socketFactory = LanSocketBinder.Factory(timeouts.connectMs)
                LanSocketBinder.withDestination(config.jumpHost) {
                    j.connect(config.jumpHost, if (config.jumpPort > 0) config.jumpPort else 22)
                }
                authSsh(j, config.jumpUser.ifBlank { config.username }, config.jumpPassword, config.jumpPrivateKey)
                c.connectVia(j.newDirectConnection(config.host, config.effectivePort))
            } else {
                LanSocketBinder.withDestination(config.host) {
                    c.connect(config.host, config.effectivePort)
                }
            }
            c.connection.keepAlive.keepAliveInterval = timeouts.keepAliveSec
            authSsh(c, config.username, config.password, config.privateKey)
            val nf = c.newSFTPClient()
            ssh = c
            sftp = nf
            jumpSsh = jump
            return nf
        } catch (e: Exception) {
            runCatching { c.disconnect() }
            runCatching { jump?.disconnect() }
            val msg = e.message ?: e.javaClass.simpleName
            if (msg.contains("host key", true) || msg.contains("HOST_KEY", true)) {
                throw FsException(tr("主机密钥与上次记录不一致，已拒绝连接（可能被中间人攻击）。如服务器重装过，请在服务器设置中重置指纹。", "Host key changed since last connection; refused (possible MITM). If the server was reinstalled, reset the fingerprint in server settings."), e)
            }
            throw FsException(tr("连接 SFTP 失败: $msg", "SFTP connection failed: $msg") + LanVpnPolicy.connectHint(), e)
        }
    }


    private fun authSsh(client: SSHClient, user: String, password: String, privateKey: String) {
        if (privateKey.isNotBlank()) {
            val kp = if (password.isNotEmpty()) {
                client.loadKeys(privateKey, null, net.schmizz.sshj.userauth.password.PasswordUtils.createOneOff(password.toCharArray()))
            } else client.loadKeys(privateKey, null, null)
            client.authPublickey(user, kp)
        } else {
            client.authPassword(user, password)
        }
    }

    private fun closeQuietly() {
        runCatching { sftp?.close() }
        runCatching { ssh?.disconnect() }
        runCatching { jumpSsh?.disconnect() }
        sftp = null
        ssh = null
        jumpSsh = null
    }

    private inline fun <T> io(what: String, block: (SFTPClient) -> T): T {
        val c = client()
        try {
            return block(c)
        } catch (e: FsException) {
            throw e
        } catch (e: net.schmizz.sshj.sftp.SFTPException) {
            throw FsException("$what: ${e.statusCode} ${e.message}", e)
        } catch (e: IOException) {
            reset()
            throw FsException("$what: ${e.message}", e)
        }
    }

    private fun perms(a: FileAttributes): String {
        val mode = a.mode?.permissionsMask ?: return ""
        val t = when (a.type) { FileMode.Type.DIRECTORY -> 'd'; FileMode.Type.SYMLINK -> 'l'; else -> '-' }
        val sb = StringBuilder().append(t)
        for (shift in intArrayOf(6, 3, 0)) {
            val v = (mode shr shift) and 7
            sb.append(if (v and 4 != 0) 'r' else '-').append(if (v and 2 != 0) 'w' else '-').append(if (v and 1 != 0) 'x' else '-')
        }
        return sb.toString()
    }

    private fun entry(path: String, name: String, a: FileAttributes, linkTargetIsDir: Boolean = false): FileEntry {
        val isDir = a.type == FileMode.Type.DIRECTORY || linkTargetIsDir
        return FileEntry(
            path = path, name = name, isDirectory = isDir,
            size = if (isDir) 0 else a.size,
            lastModified = a.mtime * 1000,
            isLink = a.type == FileMode.Type.SYMLINK,
            permissions = perms(a), owner = a.uid.toString(), group = a.gid.toString(),
        )
    }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> = io(tr("列出 $path", "List $path")) { c ->
        val dir = VPath.normalize(path)
        val out = c.ls(dir).filter { it.name != "." && it.name != ".." }.map { r ->
            val p = VPath.join(dir, r.name)
            val linkDir = r.attributes.type == FileMode.Type.SYMLINK &&
                runCatching { c.stat(p).type == FileMode.Type.DIRECTORY }.getOrDefault(false)
            entry(p, r.name, r.attributes, linkDir)
        }
        onChunk?.invoke(out)
        out
    }

    override fun stat(path: String): FileEntry? = io(tr("读取属性 $path", "Stat $path")) { c ->
        val p = VPath.normalize(path)
        val a = c.statExistence(p) ?: return@io null
        entry(p, VPath.name(p), a)
    }

    override fun openRead(path: String, offset: Long): InputStream = io(tr("打开 $path", "Open $path")) { c ->
        val rf: RemoteFile = c.open(VPath.normalize(path), EnumSet.of(OpenMode.READ))
        val inner = rf.ReadAheadRemoteFileInputStream(16, offset)
        object : FilterInputStream(inner) {
            override fun close() {
                runCatching { super.close() }
                runCatching { rf.close() }
            }
        }
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream = io(tr("写入 $path", "Write $path")) { c ->
        val modes = if (offset > 0) EnumSet.of(OpenMode.WRITE, OpenMode.CREAT)
        else EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        val rf: RemoteFile = c.open(VPath.normalize(path), modes)
        if (offset > 0) runCatching { rf.setAttributes(FileAttributes.Builder().withSize(offset).build()) }
        val inner = rf.RemoteFileOutputStream(offset, 16)
        // Keep each SFTP WRITE well under the server's max packet size.
        val chunked = object : OutputStream() {
            override fun write(b: Int) = inner.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) {
                var o = off; var left = len
                while (left > 0) { val n = minOf(left, 32 * 1024); inner.write(b, o, n); o += n; left -= n }
            }
            override fun flush() = inner.flush()
            override fun close() {
                try { inner.close() } finally { runCatching { rf.close() } }
            }
        }
        BufferedOutputStream(chunked, 32 * 1024)
    }

    override fun mkdirs(path: String) = io(tr("创建目录 $path", "Create directory $path")) { c -> c.mkdirs(VPath.normalize(path)) }

    override fun delete(path: String, recursive: Boolean) {
        val e = stat(path) ?: return
        if (e.isDirectory && !e.isLink) {
            if (recursive) for (child in list(e.path)) delete(child.path, true)
            io(tr("删除 $path", "Delete $path")) { c -> c.rmdir(e.path) }
        } else {
            io(tr("删除 $path", "Delete $path")) { c -> c.rm(e.path) }
        }
    }

    override fun rename(from: String, to: String) = io(tr("移动 $from", "Move $from")) { c ->
        c.rename(VPath.normalize(from), VPath.normalize(to))
    }

    /** Uses `cp` on the server through an exec channel; falls back to streaming if unavailable. */
    override fun serverCopy(from: String, to: String): Boolean {
        return try {
            client()
            val s = ssh ?: return false
            s.startSession().use { session ->
                val cmd = session.exec("cp -p -- ${VPath.shellQuote(VPath.normalize(from))} ${VPath.shellQuote(VPath.normalize(to))}")
                cmd.inputStream.readBytes()
                cmd.join(120, TimeUnit.SECONDS)
                cmd.exitStatus == 0
            }
        } catch (e: Exception) {
            false
        }
    }

    override fun chmod(path: String, mode: String, recursive: Boolean) = io(tr("修改权限 $path", "chmod $path")) { c ->
        require(mode.matches(Regex("[0-7]{3,4}"))) { tr("SFTP 仅支持数字权限", "SFTP supports numeric modes only") }
        c.chmod(VPath.normalize(path), mode.toInt(8))
    }

    override fun setLastModified(path: String, millis: Long) {
        if (millis <= 0) return
        runCatching {
            io(tr("设置时间", "Set time")) { c ->
                val s = millis / 1000
                c.setattr(VPath.normalize(path), FileAttributes.Builder().withAtimeMtime(s, s).build())
            }
        }
    }

    override fun reset() = synchronized(lock) { closeQuietly() }

    override fun close() = reset()

    companion object {
        fun fingerprint(key: PublicKey): String {
            val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
            return "SHA256:" + Base64.getEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(blob))
        }
    }
}
