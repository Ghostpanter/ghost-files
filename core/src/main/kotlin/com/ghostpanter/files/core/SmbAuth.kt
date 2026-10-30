package com.ghostpanter.files.core

import com.ghostpanter.files.core.net.LanSocketBinder
import com.ghostpanter.files.core.net.LanVpnPolicy
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
 *
 * Sockets go through [KeepAliveSocketFactory] / [LanSocketBinder]: when a VPN is detected at connect
 * time, LAN destinations are bound to the Wi‑Fi network so full-tunnel VPNs cannot black-hole them.
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

    fun open(host: String, port: Int, username: String, password: String, domain: String): Session =
        // Thread-local destination so every socket smbj opens on this thread (login + later
        // reconnects that also go through open) can bind to Wi‑Fi while a VPN is up.
        LanSocketBinder.withDestination(host) {
            openInner(host, port, username, password, domain)
        }

    private fun openInner(host: String, port: Int, username: String, password: String, domain: String): Session {
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
    FsException(tr("服务器不允许匿名/来宾访问，请输入用户名和密码", "Server does not allow anonymous/guest access; enter username and password"), causes.lastOrNull())

/**
 * Plain TCP sockets with keepalive, no Nagle, bounded connect timeout, and optional Wi‑Fi bind via
 * [LanSocketBinder] when a VPN is active (see [LanVpnPolicy]).
 */
typealias KeepAliveSocketFactory = LanSocketBinder.Factory
