package com.ghostpanter.files.core.server

/**
 * Built-in LAN services this phone can host so other devices connect TO us.
 *
 * Hosted SMB (others connect TO this phone on port 445) is not implemented:
 * Windows expects privileged port 445, and there is no maintained pure-Java
 * SMB2 server that fits Android. Granting Magisk Root does NOT unlock it.
 * Connecting OUT to a NAS/PC is the SMB *client* (drawer → Add server) and
 * never needs Root.
 */
enum class HostedKind(
    val label: String,
    val defaultPort: Int,
    val scheme: String,
    /** UI-only: service is unavailable (not implemented). Not tied to live Root grant. */
    val needsRoot: Boolean = false,
) {
    FTP("FTP", 2121, "ftp"),
    FTPS("FTPS", 2121, "ftps"),
    WEBDAV("WebDAV", 8080, "http"),
    WEBDAVS("WebDAV (HTTPS)", 8443, "https"),
    SFTP("SFTP", 2222, "sftp"),
    SMB("SMB", 445, "smb", needsRoot = true),
    HTTP("HTTP 远程管理", 8787, "http"),
}

data class HostedServiceConfig(
    val kind: HostedKind,
    val enabled: Boolean = false,
    val port: Int = 0,
    val username: String = "ghost",
    /** Plain password in memory; persisted encrypted by the app store. */
    val password: String = "",
    val anonymous: Boolean = false,
    /** Absolute path of the shared root directory. Empty = caller picks a default. */
    val rootPath: String = "",
    /** FTP PASV data-port range (inclusive). */
    val pasvPortStart: Int = 2122,
    val pasvPortEnd: Int = 2130,
    /** Prefer TLS (FTPS explicit / WebDAVs). Ignored for plain FTP/WebDAV/SFTP. */
    val useTls: Boolean = false,
    /** Reject clients whose remote address is not RFC1918 / link-local. */
    val lanOnly: Boolean = true,
    /** Stop automatically when Wi-Fi disconnects. */
    val stopOnWifiLost: Boolean = true,
) {
    val effectivePort: Int get() = if (port > 0) port else kind.defaultPort

    fun connectionUrl(host: String, includeAuth: Boolean = false): String {
        val user = when {
            !includeAuth -> ""
            anonymous -> ""
            username.isBlank() -> ""
            else -> username + if (password.isNotEmpty()) ":****@" else "@"
        }
        val path = when (kind) {
            HostedKind.WEBDAV, HostedKind.WEBDAVS -> "/"
            else -> "/"
        }
        return "${kind.scheme}://$user$host:${effectivePort}$path"
    }
}

data class HostedRuntimeStatus(
    val kind: HostedKind,
    val running: Boolean,
    val port: Int = 0,
    val listenAddresses: List<String> = emptyList(),
    val clientCount: Int = 0,
    val rootPath: String = "",
    val error: String? = null,
    val startedAtMs: Long = 0,
) {
    val primaryUrl: String?
        get() {
            val host = listenAddresses.firstOrNull() ?: return null
            return HostedServiceConfig(kind = kind, port = port).connectionUrl(host)
        }
}

interface HostedServer : AutoCloseable {
    val kind: HostedKind
    fun start(config: HostedServiceConfig, listenHint: String?): HostedRuntimeStatus
    fun stop()
    fun status(): HostedRuntimeStatus
    override fun close() = stop()
}

/** True when [ip] is a private / link-local IPv4 (or IPv6 ULA / link-local). */
fun isLanAddress(ip: String): Boolean {
    var s = ip.trim().removePrefix("/").substringBefore('%').removePrefix("[").removeSuffix("]")
    // IPv4-mapped IPv6 (dual-stack sockets): ::ffff:192.168.1.5
    if (s.lowercase().startsWith("::ffff:") && s.count { it == '.' } == 3) s = s.substring(7)
    if (s.contains(':')) {
        val lower = s.lowercase()
        return lower.startsWith("fe80:") || lower.startsWith("fc") || lower.startsWith("fd") || lower == "::1"
    }
    val parts = s.split('.')
    if (parts.size != 4) return false
    val a = parts[0].toIntOrNull() ?: return false
    val b = parts[1].toIntOrNull() ?: return false
    return when (a) {
        10 -> true
        127 -> true
        192 -> b == 168
        172 -> b in 16..31
        169 -> b == 254
        else -> false
    }
}

fun randomPassword(length: Int = 12): String {
    val alphabet = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val rnd = java.security.SecureRandom()
    return buildString(length) { repeat(length) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
}
