package com.ghostpanter.files.core

import com.hierynomus.mssmb2.SMB2Dialect
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.session.Session
import java.io.Closeable
import java.util.concurrent.TimeUnit

/** How an SMB session was authenticated. */
enum class SmbLogin { GUEST, ANONYMOUS, USER }

/**
 * Opens SMB sessions with the right fallbacks.
 *
 * Without credentials: Guest first, then Anonymous. smbj cannot derive SMB 3.x signing keys for a
 * null (anonymous) session — it fails with an NPE in SMBSessionBuilder.deriveKey — so the anonymous
 * attempt uses a client limited to SMB 2.0.2/2.1, where no key derivation is needed.
 */
class SmbConnector(timeouts: NetTimeouts = NetTimeouts()) : Closeable {
    private fun builder(t: NetTimeouts) = SmbConfig.builder()
        // Per-request timeout for read/write/transact futures.
        .withTimeout(t.smbRequestMs, TimeUnit.MILLISECONDS)
        // NO socket read timeout: smbj's single background reader thread treats a socket timeout
        // as a fatal transport error and fails every outstanding request on the connection. With
        // a 30 s SO_TIMEOUT, any 30 s without traffic (idle browsing, the app closing a multi-GB
        // file, local storage stalls) killed the connection -> "TransportException:
        // ExecutionException: SMBRuntimeException ...". Liveness is covered by the per-request
        // timeout above, TCP keepalive and the transfer watchdog.
        .withSoTimeout(0, TimeUnit.MILLISECONDS)
        .withSocketFactory(KeepAliveSocketFactory(t.connectMs))
        .withMultiProtocolNegotiate(true)
        .withDfsEnabled(false)
        // Sign / encrypt only when the server demands it (both cost a lot of CPU on routers/phones).
        .withSigningRequired(false)
        .withEncryptData(false)
        .withBufferSize(1024 * 1024)

    private val modern = SMBClient(builder(timeouts).build())
    private val legacy by lazy { SMBClient(builder(timeouts).withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2).build()) }

    var lastLogin: SmbLogin? = null
        private set

    fun open(host: String, port: Int, username: String, password: String, domain: String): Session {
        if (username.isNotEmpty()) {
            val ctx = { AuthenticationContext(username, password.toCharArray(), domain.ifEmpty { null }) }
            val s = try {
                modern.connect(host, port).authenticate(ctx())
            } catch (e: SMBApiException) {
                throw e // wrong password etc.: a lower dialect won't help
            } catch (e: java.net.ConnectException) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                throw e
            } catch (e: Exception) {
                // Negotiation / SMB3 key derivation problems on old or embedded servers: retry SMB 2.1/2.0.2.
                try { legacy.connect(host, port).authenticate(ctx()) } catch (e2: Exception) { e2.addSuppressed(e); throw e2 }
            }
            lastLogin = SmbLogin.USER
            return s
        }
        val errors = ArrayList<Throwable>()
        for ((kind, attempt) in listOf<Pair<SmbLogin, () -> Session>>(
            SmbLogin.GUEST to { modern.connect(host, port).authenticate(AuthenticationContext.guest()) },
            SmbLogin.ANONYMOUS to { modern.connect(host, port).authenticate(AuthenticationContext.anonymous()) },
            SmbLogin.ANONYMOUS to { legacy.connect(host, port).authenticate(AuthenticationContext.anonymous()) },
        )) {
            try {
                val s = attempt()
                lastLogin = kind
                return s
            } catch (e: java.net.ConnectException) {
                throw e
            } catch (e: java.net.UnknownHostException) {
                throw e
            } catch (e: SMBApiException) {
                errors.add(e)
            } catch (e: RuntimeException) {
                errors.add(e) // smbj NPE on SMB3 null sessions, protocol negotiation failures …
            } catch (e: java.io.IOException) {
                errors.add(e)
            }
        }
        throw SmbNoGuestException(errors)
    }

    override fun close() {
        runCatching { modern.close() }
        runCatching { legacy.close() }
    }
}

/** Neither guest nor anonymous login was accepted. */
class SmbNoGuestException(val causes: List<Throwable>) :
    FsException("服务器不允许匿名/来宾访问，请输入用户名和密码", causes.lastOrNull())

/** Plain TCP sockets with keepalive, no Nagle and a bounded connect timeout. */
class KeepAliveSocketFactory(private val connectMs: Int) : javax.net.SocketFactory() {
    private fun prep(s: java.net.Socket) = s.apply {
        keepAlive = true
        tcpNoDelay = true
        runCatching { receiveBufferSize = 1 shl 20 }
    }
    override fun createSocket(): java.net.Socket = prep(java.net.Socket())
    override fun createSocket(host: String, port: Int): java.net.Socket =
        prep(java.net.Socket()).also { it.connect(java.net.InetSocketAddress(host, port), connectMs) }
    override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress?, localPort: Int): java.net.Socket =
        prep(java.net.Socket()).also { it.bind(java.net.InetSocketAddress(localHost, localPort)); it.connect(java.net.InetSocketAddress(host, port), connectMs) }
    override fun createSocket(host: java.net.InetAddress, port: Int): java.net.Socket =
        prep(java.net.Socket()).also { it.connect(java.net.InetSocketAddress(host, port), connectMs) }
    override fun createSocket(address: java.net.InetAddress, port: Int, localAddress: java.net.InetAddress?, localPort: Int): java.net.Socket =
        prep(java.net.Socket()).also { it.bind(java.net.InetSocketAddress(localAddress, localPort)); it.connect(java.net.InetSocketAddress(address, port), connectMs) }
}
