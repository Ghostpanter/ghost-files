package com.ghostpanter.files.core.lan

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Identifies what listens on an open port: FTP/SSH by banner, WebDAV by the `DAV:` header of an
 * HTTP OPTIONS reply. Probes never send credentials.
 */
object ServiceProbe {
    private val TLS_PORTS = setOf(443, 5006, 8443)

    fun identify(host: String, port: Int, timeoutMs: Int = 1500): LanService? = when (port) {
        445, 139 -> LanService(LanServiceKind.SMB, port, LanSource.PORT)
        21, 2121 -> banner(host, port, timeoutMs)?.let { b ->
            if (b.startsWith("220")) LanService(LanServiceKind.FTP, port, LanSource.PORT, b.removePrefix("220").trim(' ', '-').take(60).ifEmpty { null }) else null
        }
        22, 2222 -> banner(host, port, timeoutMs)?.let { b ->
            if (b.startsWith("SSH-")) LanService(LanServiceKind.SFTP, port, LanSource.PORT, b.take(60)) else null
        }
        else -> http(host, port, port in TLS_PORTS, timeoutMs)
    }

    /** First line the server sends after connect (FTP "220 …", SSH "SSH-2.0-…"). */
    fun banner(host: String, port: Int, timeoutMs: Int): String? = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            s.soTimeout = timeoutMs
            readLine(s.getInputStream(), 512)
        }
    }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    fun http(host: String, port: Int, tls: Boolean, timeoutMs: Int): LanService? = runCatching {
        val raw = Socket()
        raw.connect(InetSocketAddress(host, port), timeoutMs)
        raw.soTimeout = timeoutMs
        val s: Socket = if (tls) {
            // Probe only (no credentials are sent), so any certificate is acceptable here; the real
            // connection later goes through the normal WebDAV client and its TLS checks.
            val ctx = SSLContext.getInstance("TLS")
            ctx.init(null, arrayOf(TrustAll), null)
            (ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket).also { it.startHandshake() }
        } else raw
        s.use {
            val req = "OPTIONS / HTTP/1.1\r\nHost: $host:$port\r\nUser-Agent: GhostFiles-LanScan\r\nConnection: close\r\n\r\n"
            it.getOutputStream().apply { write(req.toByteArray(Charsets.ISO_8859_1)); flush() }
            parseHttpHead(it.getInputStream(), port, tls)
        }
    }.getOrNull()

    /** Parses status line + headers and maps them to a [LanService]. Visible for tests. */
    fun parseHttpHead(input: InputStream, port: Int, tls: Boolean): LanService? {
        val status = readLine(input, 1024) ?: return null
        if (!status.startsWith("HTTP/")) return null
        var dav: String? = null
        var server: String? = null
        var allow: String? = null
        while (true) {
            val line = readLine(input, 4096) ?: break
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i <= 0) continue
            val k = line.substring(0, i).trim().lowercase()
            val v = line.substring(i + 1).trim()
            when (k) {
                "dav" -> dav = v
                "server" -> server = v
                "allow", "public" -> allow = (allow?.plus(",") ?: "") + v
            }
        }
        val isDav = dav != null || allow?.uppercase()?.contains("PROPFIND") == true
        val kind = when {
            isDav && tls -> LanServiceKind.WEBDAVS
            isDav -> LanServiceKind.WEBDAV
            tls -> LanServiceKind.HTTPS
            else -> LanServiceKind.HTTP
        }
        return LanService(kind, port, LanSource.PORT, server?.take(60))
    }

    private fun readLine(input: InputStream, max: Int): String? {
        val sb = StringBuilder()
        while (sb.length < max) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private object TrustAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
