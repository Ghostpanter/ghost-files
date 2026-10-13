package com.ghostpanter.files.core.server

import com.ghostpanter.files.core.server.smb2.Smb2Server
import java.io.File
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Hosted SMB2 so LAN clients can connect TO this phone.
 *
 * Listens on a **non-privileged** TCP port (default [HostedKind.SMB] = 4455) — no Root required.
 * Stable dialects: **SMB 2.0.2 / 2.1** (HMAC-SHA256 signing). SMB 3.0/3.0.2 is attempted only
 * when the client offers no 2.x dialect; SMB3 encryption is not advertised.
 *
 * Windows Explorer still expects port 445 by default. On **Windows 11 24H2+** use:
 * `New-SmbMapping -LocalPath Z: -RemotePath \\IP\share -TcpPort 4455 -UserName ghost`
 * Otherwise use `smbclient -p 4455`, macOS Finder with `smb://IP:4455/share`, or any SMB2 client
 * that allows a custom port. Phone SMB *client* (drawer → Add server) is separate (smbj).
 */
class SmbHostedServer : HostedServer {
    override val kind: HostedKind = HostedKind.SMB

    private val state = AtomicReference<Running?>(null)
    private var lastError: String? = null

    private data class Running(
        val config: HostedServiceConfig,
        val server: Smb2Server,
        val startedAt: Long,
        val listenHint: String?,
    )

    override fun start(config: HostedServiceConfig, listenHint: String?): HostedRuntimeStatus {
        stop()
        lastError = null
        val root = File(config.rootPath)
        if (!root.isDirectory && !root.mkdirs()) {
            lastError = "共享目录不可用: ${config.rootPath}"
            return status()
        }
        return try {
            val hostName = sanitizeHostName(listenHint)
            val bind = listenHint?.takeIf { it.isNotBlank() }?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
            val srv = Smb2Server(
                shareRoot = root,
                shareName = SHARE_NAME,
                username = config.username.ifBlank { "ghost" },
                password = config.password,
                anonymous = config.anonymous,
                serverName = hostName,
                bindAddress = bind,
            )
            srv.start(config.effectivePort)
            Thread.sleep(50)
            if (srv.lastError != null) {
                lastError = srv.lastError
                runCatching { srv.close() }
                return status()
            }
            state.set(Running(config, srv, System.currentTimeMillis(), listenHint))
            status()
        } catch (e: Exception) {
            lastError = e.message?.take(200) ?: e.javaClass.simpleName
            runCatching { stop() }
            status()
        }
    }

    override fun stop() {
        val r = state.getAndSet(null) ?: return
        runCatching { r.server.close() }
    }

    override fun status(): HostedRuntimeStatus {
        val r = state.get()
        val addrs = buildList {
            r?.listenHint?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        val err = lastError ?: r?.server?.lastError
        return HostedRuntimeStatus(
            kind = HostedKind.SMB,
            running = r != null,
            port = r?.config?.effectivePort ?: 0,
            listenAddresses = addrs,
            clientCount = r?.server?.activeClients ?: 0,
            rootPath = r?.config?.rootPath.orEmpty(),
            error = err,
            startedAtMs = r?.startedAt ?: 0,
        )
    }

    companion object {
        const val SHARE_NAME = "share"

        private fun sanitizeHostName(hint: String?): String {
            val raw = (hint?.substringBefore('.')?.filter { it.isLetterOrDigit() || it == '-' }
                ?.take(15)
                ?.uppercase())
            return if (raw.isNullOrBlank()) "GHOSTFILES" else raw
        }
    }
}
