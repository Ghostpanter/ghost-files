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
        .withTimeout(t.readMs.toLong(), TimeUnit.MILLISECONDS)
        .withSoTimeout(t.readMs.toLong(), TimeUnit.MILLISECONDS)
        .withMultiProtocolNegotiate(true)
        .withDfsEnabled(false)

    private val modern = SMBClient(builder(timeouts).build())
    private val legacy by lazy { SMBClient(builder(timeouts).withDialects(SMB2Dialect.SMB_2_1, SMB2Dialect.SMB_2_0_2).build()) }

    var lastLogin: SmbLogin? = null
        private set

    fun open(host: String, port: Int, username: String, password: String, domain: String): Session {
        if (username.isNotEmpty()) {
            val s = modern.connect(host, port).authenticate(AuthenticationContext(username, password.toCharArray(), domain.ifEmpty { null }))
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
