package com.ghostpanter.files.core

enum class Protocol(val label: String, val defaultPort: Int) {
    SMB("SMB", 445),
    FTP("FTP", 21),
    FTPS("FTPS", 21),
    SFTP("SFTP", 22),
    WEBDAV("WebDAV", 80),
    WEBDAVS("WebDAV (HTTPS)", 443),
}

data class ServerConfig(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val host: String,
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    /**
     * SMB: share names separated by ',' (e.g. "public,media").
     * FTP/SFTP: start directory. WebDAV: base path such as "/dav".
     */
    val path: String = "",
    val domain: String = "",
    val encoding: String = "UTF-8",
    val passiveMode: Boolean = true,
    /** SFTP: OpenSSH/PEM private key text (optional). */
    val privateKey: String = "",
    val anonymous: Boolean = false,
    /** SFTP ProxyJump / bastion host (optional). */
    val jumpHost: String = "",
    val jumpPort: Int = 22,
    val jumpUser: String = "",
    val jumpPassword: String = "",
    val jumpPrivateKey: String = "",
) {
    val effectivePort: Int get() = if (port > 0) port else protocol.defaultPort
    val fsId: String get() = "net:$id"
}

/** Stores trusted SSH host key fingerprints (trust-on-first-use). */
interface HostKeyStore {
    fun get(hostPort: String): String?
    fun put(hostPort: String, fingerprint: String)
}

class MemoryHostKeyStore : HostKeyStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(hostPort: String) = map[hostPort]
    override fun put(hostPort: String, fingerprint: String) { map[hostPort] = fingerprint }
}

/** Shared network timeouts; tuned so a dead LAN peer is detected quickly instead of hanging. */
data class NetTimeouts(
    val connectMs: Int = 10_000,
    val readMs: Int = 30_000,
    val keepAliveSec: Int = 15,
) {
    /** Per-request SMB timeout (read/write/transact futures). */
    val smbRequestMs: Long get() = readMs * 3L

    /** A single SMB data read silent this long counts as stalled -> reconnect and resume. */
    val smbReadStallMs: Long get() = readMs * 2L / 3 // 20 s by default
}

object RemoteFsFactory {
    fun create(
        config: ServerConfig,
        hostKeys: HostKeyStore,
        timeouts: NetTimeouts = NetTimeouts(),
        sshConfig: (() -> net.schmizz.sshj.Config)? = null,
        /** True for a connection owned by a single transfer job (closed when the job ends). */
        dedicated: Boolean = false,
    ): VirtualFs = when (config.protocol) {
        Protocol.SMB -> SmbFs(config, timeouts, dedicated)
        Protocol.FTP, Protocol.FTPS -> FtpFs(config, timeouts)
        Protocol.SFTP -> SftpFs(config, hostKeys, timeouts, sshConfig ?: { net.schmizz.sshj.DefaultConfig() })
        Protocol.WEBDAV, Protocol.WEBDAVS -> WebDavFs(config, timeouts)
    }
}
