package com.ghostpanter.files.core.server

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-local registry of hosted servers. The Android foreground service drives
 * start/stop; unit tests talk to the hub directly.
 */
class HostedServerHub(
    private val dataDir: File,
    private val defaultRoot: () -> String,
) {
    private val servers = ConcurrentHashMap<HostedKind, HostedServer>()
    private val configs = ConcurrentHashMap<HostedKind, HostedServiceConfig>()
    private var listenHint: String? = null

    fun updateListenHint(ip: String?) { listenHint = ip }

    fun getConfig(kind: HostedKind): HostedServiceConfig =
        configs[kind] ?: defaultConfig(kind)

    fun putConfig(config: HostedServiceConfig) {
        configs[config.kind] = config
        // FTPS shares settings with FTP; WEBDAVS with WEBDAV
        when (config.kind) {
            HostedKind.FTP -> configs[HostedKind.FTPS] = config.copy(kind = HostedKind.FTPS, useTls = true)
            HostedKind.FTPS -> configs[HostedKind.FTP] = config.copy(kind = HostedKind.FTP, useTls = false)
            HostedKind.WEBDAV -> configs[HostedKind.WEBDAVS] = config.copy(kind = HostedKind.WEBDAVS, useTls = true)
            HostedKind.WEBDAVS -> configs[HostedKind.WEBDAV] = config.copy(kind = HostedKind.WEBDAV, useTls = false)
            else -> {}
        }
    }

    fun defaultConfig(kind: HostedKind): HostedServiceConfig = HostedServiceConfig(
        kind = kind,
        port = kind.defaultPort,
        username = "ghost",
        password = randomPassword(),
        rootPath = defaultRoot(),
        useTls = kind == HostedKind.FTPS || kind == HostedKind.WEBDAVS,
        lanOnly = true,
        stopOnWifiLost = true,
    )

    fun status(kind: HostedKind): HostedRuntimeStatus =
        servers[kind]?.status() ?: HostedRuntimeStatus(kind = kind, running = false)

    fun allStatus(): List<HostedRuntimeStatus> =
        listOf(HostedKind.FTP, HostedKind.FTPS, HostedKind.WEBDAV, HostedKind.WEBDAVS, HostedKind.SFTP, HostedKind.SMB, HostedKind.HTTP)
            .map { status(it) }

    @Synchronized
    fun start(kind: HostedKind): HostedRuntimeStatus {
        if (kind == HostedKind.SMB) {
            return HostedRuntimeStatus(
                kind = kind, running = false,
                error = "SMB 服务需 Root（需绑定 445；Android 无可用的免 Root 纯 Java SMB2 服务端）",
            )
        }
        val cfg = getConfig(kind).let {
            val root = it.rootPath.ifBlank { defaultRoot() }
            it.copy(rootPath = root, enabled = true)
        }
        putConfig(cfg)
        val server = servers.getOrPut(kind) { create(kind) }
        return server.start(cfg, listenHint)
    }

    @Synchronized
    fun stop(kind: HostedKind): HostedRuntimeStatus {
        servers[kind]?.stop()
        val cfg = getConfig(kind).copy(enabled = false)
        putConfig(cfg)
        return status(kind)
    }

    @Synchronized
    fun stopAll() {
        servers.values.forEach { runCatching { it.stop() } }
    }

    fun anyRunning(): Boolean = servers.values.any { it.status().running }

    fun runningCount(): Int = servers.values.count { it.status().running }

    private fun create(kind: HostedKind): HostedServer = when (kind) {
        HostedKind.FTP, HostedKind.FTPS -> FtpHostedServer(File(dataDir, "hosted-tls.p12"))
        HostedKind.WEBDAV, HostedKind.WEBDAVS -> WebDavHostedServer(File(dataDir, "hosted-tls.p12"))
        HostedKind.SFTP -> SftpHostedServer(File(dataDir, "hosted-sftp-hostkey.ser"))
        HostedKind.SMB -> error("SMB not implemented")
        HostedKind.HTTP -> HttpRemoteServer()
    }
}
