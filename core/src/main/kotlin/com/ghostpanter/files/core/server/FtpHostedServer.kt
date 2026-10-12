package com.ghostpanter.files.core.server

import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpException
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.ssl.SslConfigurationFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission
import org.apache.ftpserver.usermanager.impl.WritePermission
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FtpHostedServer(
    private val tlsKeyStoreFile: File? = null,
) : HostedServer {
    override val kind: HostedKind get() = activeKind
    private var activeKind: HostedKind = HostedKind.FTP
    private var server: FtpServer? = null
    private var config: HostedServiceConfig? = null
    private var startedAt = 0L
    private var listenHint: String? = null
    private val clients = ConcurrentHashMap.newKeySet<String>()
    private val clientCount = AtomicInteger(0)
    private var lastError: String? = null

    override fun start(config: HostedServiceConfig, listenHint: String?): HostedRuntimeStatus {
        stop()
        this.config = config
        this.listenHint = listenHint
        activeKind = if (config.useTls || config.kind == HostedKind.FTPS) HostedKind.FTPS else HostedKind.FTP
        val root = File(config.rootPath)
        if (!root.isDirectory && !root.mkdirs()) {
            lastError = "共享目录不可用: ${config.rootPath}"
            return status()
        }
        try {
            val factory = FtpServerFactory()
            val listener = ListenerFactory().apply {
                port = config.effectivePort
                val data = DataConnectionConfigurationFactory().apply {
                    idleTime = 300
                    isPassiveIpCheck = false
                    passivePorts = "${config.pasvPortStart}-${config.pasvPortEnd}"
                    if (!listenHint.isNullOrBlank()) passiveExternalAddress = listenHint
                }
                dataConnectionConfiguration = data.createDataConnectionConfiguration()
                if (activeKind == HostedKind.FTPS) {
                    val ks = tlsKeyStoreFile ?: error("FTPS requires a keystore file")
                    SelfSignedTls.ensureKeyStore(ks)
                    val ssl = SslConfigurationFactory().apply {
                        keystoreFile = ks
                        keystorePassword = String(SelfSignedTls.keyStorePassword())
                    }
                    sslConfiguration = ssl.createSslConfiguration()
                    isImplicitSsl = false // AUTH TLS (explicit FTPS)
                }
            }
            factory.addListener("default", listener.createListener())

            val user = BaseUser().apply {
                name = if (config.anonymous) "anonymous" else config.username.ifBlank { "ghost" }
                password = if (config.anonymous) "" else config.password
                homeDirectory = root.absolutePath
                authorities = listOf<Authority>(
                    WritePermission(),
                    ConcurrentLoginPermission(8, 8),
                )
            }
            factory.userManager.save(user)
            if (config.anonymous && config.username.isNotBlank() && config.username != "anonymous") {
                // Also accept the named account
                val named = BaseUser().apply {
                    name = config.username
                    password = config.password
                    homeDirectory = root.absolutePath
                    authorities = listOf<Authority>(WritePermission(), ConcurrentLoginPermission(8, 8))
                }
                factory.userManager.save(named)
            }

            factory.ftplets = mapOf("ghost" to object : DefaultFtplet() {
                override fun onConnect(session: FtpSession): FtpletResult {
                    val ip = session.clientAddress?.address?.hostAddress ?: return FtpletResult.DEFAULT
                    if (config.lanOnly && !isLanAddress(ip)) {
                        return FtpletResult.DISCONNECT
                    }
                    clients.add(session.sessionId.toString())
                    clientCount.set(clients.size)
                    return FtpletResult.DEFAULT
                }

                override fun onDisconnect(session: FtpSession): FtpletResult {
                    clients.remove(session.sessionId.toString())
                    clientCount.set(clients.size)
                    return FtpletResult.DEFAULT
                }

                @Throws(FtpException::class)
                override fun beforeCommand(session: FtpSession, request: FtpRequest): FtpletResult =
                    FtpletResult.DEFAULT
            })

            val s = factory.createServer()
            s.start()
            server = s
            startedAt = System.currentTimeMillis()
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            server = null
        }
        return status()
    }

    override fun stop() {
        runCatching { server?.stop() }
        server = null
        clients.clear()
        clientCount.set(0)
        startedAt = 0
    }

    override fun status(): HostedRuntimeStatus {
        val c = config
        val running = server != null && server?.isStopped == false
        return HostedRuntimeStatus(
            kind = activeKind,
            running = running,
            port = c?.effectivePort ?: 0,
            listenAddresses = if (running) listOfNotNull(listenHint) else emptyList(),
            clientCount = clientCount.get(),
            rootPath = c?.rootPath ?: "",
            error = lastError,
            startedAtMs = startedAt,
        )
    }
}
