package com.ghostpanter.files.core.server

import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.session.Session
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.auth.password.PasswordAuthenticator
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import org.apache.sshd.sftp.server.SftpSubsystemFactory
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SftpHostedServer(
    private val hostKeyFile: File,
) : HostedServer {
    override val kind: HostedKind = HostedKind.SFTP
    private var sshd: SshServer? = null
    private var config: HostedServiceConfig? = null
    private var startedAt = 0L
    private var listenHint: String? = null
    private val sessions = ConcurrentHashMap.newKeySet<String>()
    private val clientCount = AtomicInteger(0)
    private var lastError: String? = null

    override fun start(config: HostedServiceConfig, listenHint: String?): HostedRuntimeStatus {
        stop()
        this.config = config
        this.listenHint = listenHint
        val root = File(config.rootPath)
        if (!root.isDirectory && !root.mkdirs()) {
            lastError = "共享目录不可用: ${config.rootPath}"
            return status()
        }
        try {
            hostKeyFile.parentFile?.mkdirs()
            val server = SshServer.setUpDefaultServer()
            server.port = config.effectivePort
            server.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile.toPath())
            server.subsystemFactories = listOf(SftpSubsystemFactory())
            server.fileSystemFactory = VirtualFileSystemFactory(root.toPath())
            val expectedUser = if (config.anonymous) "anonymous" else config.username.ifBlank { "ghost" }
            val expectedPass = if (config.anonymous) "" else config.password
            server.passwordAuthenticator = PasswordAuthenticator { username, password, session ->
                val addr = (session?.remoteAddress as? InetSocketAddress)?.address?.hostAddress
                if (config.lanOnly && addr != null && !isLanAddress(addr)) return@PasswordAuthenticator false
                if (config.anonymous) return@PasswordAuthenticator true
                username == expectedUser && password == expectedPass
            }
            // Also allow empty password auth only when anonymous
            if (config.anonymous) {
                server.setPublickeyAuthenticator { _, _, _ -> false }
            }
            server.addSessionListener(object : SessionListener {
                override fun sessionCreated(session: Session) {
                    val id = session.ioSession?.id?.toString() ?: session.toString()
                    val addr = (session.remoteAddress as? InetSocketAddress)?.address?.hostAddress
                    if (config.lanOnly && addr != null && !isLanAddress(addr)) {
                        runCatching { session.close(true) }
                        return
                    }
                    sessions.add(id)
                    clientCount.set(sessions.size)
                }

                override fun sessionClosed(session: Session) {
                    val id = session.ioSession?.id?.toString() ?: session.toString()
                    sessions.remove(id)
                    clientCount.set(sessions.size)
                }
            })
            server.start()
            sshd = server
            startedAt = System.currentTimeMillis()
            lastError = null
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            sshd = null
        }
        return status()
    }

    override fun stop() {
        runCatching { sshd?.stop(true) }
        sshd = null
        sessions.clear()
        clientCount.set(0)
        startedAt = 0
    }

    override fun status(): HostedRuntimeStatus {
        val c = config
        val running = sshd?.isStarted == true
        return HostedRuntimeStatus(
            kind = HostedKind.SFTP,
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
