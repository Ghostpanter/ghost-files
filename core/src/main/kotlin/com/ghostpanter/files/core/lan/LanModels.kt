package com.ghostpanter.files.core.lan

import com.ghostpanter.files.core.Protocol

/** What a discovered port / mDNS record most likely is. */
enum class LanServiceKind(val label: String, val protocol: Protocol?) {
    SMB("SMB", Protocol.SMB),
    FTP("FTP", Protocol.FTP),
    SFTP("SFTP", Protocol.SFTP),
    WEBDAV("WebDAV", Protocol.WEBDAV),
    WEBDAVS("WebDAV (HTTPS)", Protocol.WEBDAVS),
    /** An HTTP(S) port without a DAV header: may still host WebDAV under a sub path. */
    HTTP("HTTP", Protocol.WEBDAV),
    HTTPS("HTTPS", Protocol.WEBDAVS),
}

enum class LanSource { MDNS, NETBIOS, PORT, WSD }

data class LanService(
    val kind: LanServiceKind,
    val port: Int,
    val source: LanSource,
    /** Banner / server header / mDNS instance name / WebDAV path. */
    val detail: String? = null,
    /** Base path advertised by mDNS TXT "path=" (WebDAV), if any. */
    val path: String? = null,
)

data class LanHost(
    val ip: String,
    val hostname: String? = null,
    val workgroup: String? = null,
    val services: List<LanService> = emptyList(),
) {
    val displayName: String get() = hostname ?: ip
}

/**
 * Thread-safe accumulator that merges results from all discovery methods by IP.
 * Every mutation returns the new sorted snapshot so a UI can publish it directly.
 */
class LanResults {
    private val hosts = LinkedHashMap<String, LanHost>()

    @Synchronized
    fun add(ip: String, hostname: String? = null, workgroup: String? = null, service: LanService? = null): List<LanHost> {
        val old = hosts[ip] ?: LanHost(ip)
        var services = old.services
        if (service != null) {
            val same = services.indexOfFirst { it.kind == service.kind && it.port == service.port }
            services = when {
                same < 0 -> services + service
                // Prefer the richer record (mDNS names, DAV-confirmed probe) over a bare port hit.
                (services[same].detail == null && service.detail != null) || (services[same].path == null && service.path != null) ->
                    services.toMutableList().also { it[same] = service.copy(detail = service.detail ?: it[same].detail, path = service.path ?: it[same].path) }
                else -> services
            }
            // A confirmed WebDAV on a port supersedes the plain HTTP guess for that port.
            if (service.kind == LanServiceKind.WEBDAV || service.kind == LanServiceKind.WEBDAVS) {
                services = services.filterNot { (it.kind == LanServiceKind.HTTP || it.kind == LanServiceKind.HTTPS) && it.port == service.port }
            }
        }
        hosts[ip] = old.copy(
            hostname = old.hostname ?: hostname?.takeIf { it.isNotBlank() },
            workgroup = old.workgroup ?: workgroup?.takeIf { it.isNotBlank() },
            services = services.sortedWith(compareBy({ it.kind.ordinal }, { it.port })),
        )
        return snapshot()
    }

    @Synchronized
    fun snapshot(): List<LanHost> = hosts.values
        .filter { it.services.isNotEmpty() || it.hostname != null }
        .sortedWith(compareBy({ it.services.isEmpty() }, { ipKey(it.ip) }))

    companion object {
        fun ipKey(ip: String): Long = ip.split('.').mapNotNull { it.toLongOrNull() }.takeIf { it.size == 4 }
            ?.fold(0L) { acc, p -> acc * 256 + p } ?: Long.MAX_VALUE
    }
}
