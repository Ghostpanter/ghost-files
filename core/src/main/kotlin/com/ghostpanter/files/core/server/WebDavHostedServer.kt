package com.ghostpanter.files.core.server

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLServerSocketFactory

/**
 * Lightweight Class-1 WebDAV server (PROPFIND/GET/PUT/DELETE/MKCOL/MOVE/COPY/HEAD/OPTIONS)
 * with HTTP Basic auth. Optional TLS via [SelfSignedTls].
 */
class WebDavHostedServer(
    private val tlsKeyStoreFile: File? = null,
) : HostedServer {
    override val kind: HostedKind get() = activeKind
    private var activeKind: HostedKind = HostedKind.WEBDAV
    private var config: HostedServiceConfig? = null
    private var serverSocket: ServerSocket? = null
    private var startedAt = 0L
    private var listenHint: String? = null
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<String>()
    private val clientCount = AtomicInteger(0)
    private var lastError: String? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "ghost-webdav").apply { isDaemon = true }
    }
    private var acceptThread: Thread? = null

    override fun start(config: HostedServiceConfig, listenHint: String?): HostedRuntimeStatus {
        stop()
        this.config = config
        this.listenHint = listenHint
        activeKind = if (config.useTls || config.kind == HostedKind.WEBDAVS) HostedKind.WEBDAVS else HostedKind.WEBDAV
        val root = File(config.rootPath)
        if (!root.isDirectory && !root.mkdirs()) {
            lastError = "共享目录不可用: ${config.rootPath}"
            return status()
        }
        try {
            val ss = if (activeKind == HostedKind.WEBDAVS) {
                val ks = tlsKeyStoreFile ?: error("WebDAVs requires a keystore file")
                val ctx = SelfSignedTls.sslContext(SelfSignedTls.ensureKeyStore(ks))
                (ctx.serverSocketFactory as SSLServerSocketFactory).createServerSocket(config.effectivePort)
            } else {
                ServerSocket(config.effectivePort)
            }
            ss.soTimeout = 1000
            serverSocket = ss
            running.set(true)
            startedAt = System.currentTimeMillis()
            lastError = null
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val sock = ss.accept()
                        pool.execute { handleClient(sock, root, config) }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: IOException) {
                        if (!running.get()) break
                    }
                }
            }, "ghost-webdav-accept").also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            running.set(false)
            serverSocket = null
        }
        return status()
    }

    private fun handleClient(sock: Socket, root: File, config: HostedServiceConfig) {
        val id = sock.remoteSocketAddress?.toString() ?: sock.toString()
        try {
            val ip = (sock.inetAddress?.hostAddress) ?: ""
            if (config.lanOnly && ip.isNotEmpty() && !isLanAddress(ip)) {
                sock.close()
                return
            }
            clients.add(id)
            clientCount.set(clients.size)
            sock.soTimeout = 60_000
            val input = BufferedInputStream(sock.getInputStream())
            val output = BufferedOutputStream(sock.getOutputStream())
            while (running.get() && !sock.isClosed) {
                val req = readRequest(input) ?: break
                if (!authorized(req, config)) {
                    writeResponse(output, 401, mapOf(
                        "WWW-Authenticate" to "Basic realm=\"Ghost Files\"",
                        "Content-Length" to "0",
                    ), ByteArray(0))
                    continue
                }
                if (req.headers["expect"]?.equals("100-continue", true) == true) {
                    output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.flush()
                }
                dispatch(req, root, output)
                if (req.headers["connection"]?.equals("close", true) == true) break
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(id)
            clientCount.set(clients.size)
            runCatching { sock.close() }
        }
    }

    private fun authorized(req: HttpRequest, config: HostedServiceConfig): Boolean {
        if (config.anonymous) return true
        val auth = req.headers["authorization"] ?: return false
        if (!auth.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(auth.substring(6).trim()), StandardCharsets.UTF_8)
        }.getOrNull() ?: return false
        val user = decoded.substringBefore(':')
        val pass = decoded.substringAfter(':', missingDelimiterValue = "")
        val expectedUser = config.username.ifBlank { "ghost" }
        return user == expectedUser && pass == config.password
    }

    private fun dispatch(req: HttpRequest, root: File, out: OutputStream) {
        val path = sanitize(req.path)
        val file = fileOf(root, path)
        // PROPFIND (and similar) may carry an XML body — consume it before responding
        // so the keep-alive stream stays aligned for the next request.
        if (req.method != "PUT" && (req.contentLength > 0 || req.chunked)) {
            runCatching { copyBody(req, OutputStream.nullOutputStream()) }
        }
        when (req.method) {
            "OPTIONS" -> writeResponse(out, 200, mapOf(
                "Allow" to "OPTIONS, GET, HEAD, PUT, DELETE, MKCOL, PROPFIND, MOVE, COPY",
                "DAV" to "1",
                "Content-Length" to "0",
            ), ByteArray(0))
            "PROPFIND" -> {
                if (!file.exists()) { writeResponse(out, 404, emptyMap(), ByteArray(0)); return }
                val depth = req.headers["depth"] ?: "1"
                val items = mutableListOf(file)
                if (depth != "0" && file.isDirectory) {
                    file.listFiles()?.sortedBy { it.name }?.let { items.addAll(it) }
                }
                val body = buildString {
                    append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                    append("<d:multistatus xmlns:d=\"DAV:\">")
                    items.forEach { append(propXml(it, root)) }
                    append("</d:multistatus>")
                }.toByteArray(StandardCharsets.UTF_8)
                writeResponse(out, 207, mapOf("Content-Type" to "application/xml; charset=utf-8"), body)
            }
            "GET", "HEAD" -> {
                if (!file.isFile) { writeResponse(out, 404, emptyMap(), ByteArray(0)); return }
                val range = req.headers["range"]
                if (range != null && range.startsWith("bytes=")) {
                    val start = range.removePrefix("bytes=").substringBefore('-').toLongOrNull() ?: 0L
                    val len = file.length() - start
                    val headers = mutableMapOf(
                        "Content-Type" to "application/octet-stream",
                        "Accept-Ranges" to "bytes",
                        "Content-Range" to "bytes $start-${file.length() - 1}/${file.length()}",
                        "Content-Length" to len.toString(),
                    )
                    if (req.method == "HEAD") writeResponse(out, 206, headers, ByteArray(0))
                    else writeFile(out, 206, headers, file, start, len)
                } else {
                    val headers = mapOf(
                        "Content-Type" to "application/octet-stream",
                        "Accept-Ranges" to "bytes",
                        "Content-Length" to file.length().toString(),
                    )
                    if (req.method == "HEAD") writeResponse(out, 200, headers, ByteArray(0))
                    else writeFile(out, 200, headers, file, 0, file.length())
                }
            }
            "PUT" -> {
                file.parentFile?.mkdirs()
                file.outputStream().use { dest ->
                    copyBody(req, dest)
                }
                writeResponse(out, 201, mapOf("Content-Length" to "0"), ByteArray(0))
            }
            "MKCOL" -> {
                if (file.exists()) writeResponse(out, 405, emptyMap(), ByteArray(0))
                else if (file.mkdirs()) writeResponse(out, 201, mapOf("Content-Length" to "0"), ByteArray(0))
                else writeResponse(out, 409, emptyMap(), ByteArray(0))
            }
            "DELETE" -> {
                if (!file.exists()) writeResponse(out, 404, emptyMap(), ByteArray(0))
                else if (file.deleteRecursively()) writeResponse(out, 204, mapOf("Content-Length" to "0"), ByteArray(0))
                else writeResponse(out, 500, emptyMap(), ByteArray(0))
            }
            "MOVE", "COPY" -> {
                val destHeader = req.headers["destination"] ?: run {
                    writeResponse(out, 400, emptyMap(), ByteArray(0)); return
                }
                val destPath = sanitize(java.net.URI(destHeader).rawPath ?: "/")
                val dest = fileOf(root, destPath)
                if (dest.exists() && req.headers["overwrite"] == "F") {
                    writeResponse(out, 412, emptyMap(), ByteArray(0)); return
                }
                dest.parentFile?.mkdirs()
                dest.deleteRecursively()
                val ok = if (req.method == "MOVE") file.renameTo(dest) || run {
                    file.copyRecursively(dest); file.deleteRecursively()
                } else {
                    file.copyRecursively(dest); true
                }
                writeResponse(out, if (ok) 201 else 500, mapOf("Content-Length" to "0"), ByteArray(0))
            }
            else -> writeResponse(out, 405, emptyMap(), ByteArray(0))
        }
    }

    private fun writeFile(out: OutputStream, code: Int, headers: Map<String, String>, file: File, start: Long, length: Long) {
        val head = buildHeader(code, headers)
        out.write(head)
        file.inputStream().use { input ->
            if (start > 0) input.skip(start)
            var left = length
            val buf = ByteArray(64 * 1024)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        out.flush()
    }

    private fun writeResponse(out: OutputStream, code: Int, headers: Map<String, String>, body: ByteArray) {
        val h = headers.toMutableMap()
        h.putIfAbsent("Content-Length", body.size.toString())
        out.write(buildHeader(code, h))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun buildHeader(code: Int, headers: Map<String, String>): ByteArray {
        val reason = when (code) {
            200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 206 -> "Partial Content"
            207 -> "Multi-Status"; 400 -> "Bad Request"; 401 -> "Unauthorized"
            404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
            412 -> "Precondition Failed"; else -> "Error"
        }
        return buildString {
            append("HTTP/1.1 $code $reason\r\n")
            append("Server: Ghost-Files-WebDAV\r\n")
            append("Connection: keep-alive\r\n")
            headers.forEach { (k, v) -> append("$k: $v\r\n") }
            append("\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
    }

    private fun propXml(f: File, root: File): String {
        val rel = if (f == root) "/" else {
            val p = "/" + f.absolutePath.removePrefix(root.absolutePath).replace('\\', '/').trimStart('/')
            if (f.isDirectory && !p.endsWith("/")) "$p/" else p
        }
        val href = rel.split('/').joinToString("/") {
            if (it.isEmpty()) "" else URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }.let { if (it.startsWith("/")) it else "/$it" }
        val modified = httpDate(f.lastModified())
        return buildString {
            append("<d:response><d:href>").append(href).append("</d:href><d:propstat><d:prop>")
            append("<d:displayname>").append(xmlEsc(f.name.ifEmpty { "/" })).append("</d:displayname>")
            append("<d:getlastmodified>").append(modified).append("</d:getlastmodified>")
            if (f.isDirectory) append("<d:resourcetype><d:collection/></d:resourcetype>")
            else {
                append("<d:resourcetype/><d:getcontentlength>").append(f.length()).append("</d:getcontentlength>")
                append("<d:getcontenttype>application/octet-stream</d:getcontenttype>")
            }
            append("</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>")
        }
    }

    private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun httpDate(ms: Long): String {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("GMT")
        return fmt.format(Date(ms))
    }

    private fun sanitize(raw: String): String {
        val decoded = URLDecoder.decode(raw.substringBefore('?'), "UTF-8")
        val parts = decoded.split('/').filter { it.isNotEmpty() && it != "." && it != ".." }
        return "/" + parts.joinToString("/")
    }

    private fun fileOf(root: File, path: String): File {
        val f = File(root, path.trimStart('/')).canonicalFile
        val rootCanon = root.canonicalFile
        if (!f.path.startsWith(rootCanon.path)) throw IOException("path escape")
        return f
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val contentLength: Long,
        val chunked: Boolean,
        val body: InputStream,
    )

    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val line = readLine(input) ?: return null
        if (line.isEmpty()) return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase(Locale.US)] = h.substring(i + 1).trim()
        }
        val te = headers["transfer-encoding"]?.lowercase(Locale.US).orEmpty()
        val chunked = te.contains("chunked")
        val len = headers["content-length"]?.toLongOrNull() ?: if (chunked) -1L else 0L
        val pathOnly = parts[1].substringBefore('?')
        // Strip absolute URL form if a proxy-style request line is used.
        val path = if (pathOnly.startsWith("http://") || pathOnly.startsWith("https://")) {
            java.net.URI(pathOnly).rawPath ?: "/"
        } else pathOnly
        return HttpRequest(parts[0].uppercase(Locale.US), path, headers, len, chunked, input)
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(StandardCharsets.US_ASCII)
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString(StandardCharsets.US_ASCII)
    }


    private fun copyBody(req: HttpRequest, dest: OutputStream) {
        val buf = ByteArray(64 * 1024)
        val input = req.body as? BufferedInputStream ?: BufferedInputStream(req.body)
        if (req.chunked) {
            while (true) {
                val sizeLine = readLine(input)?.trim() ?: break
                val size = sizeLine.substringBefore(';').toIntOrNull(16) ?: break
                if (size == 0) {
                    while (true) {
                        val trailer = readLine(input) ?: break
                        if (trailer.isEmpty()) break
                    }
                    break
                }
                var left = size
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size, left))
                    if (n < 0) break
                    dest.write(buf, 0, n)
                    left -= n
                }
                input.read(); input.read() // CRLF after chunk
            }
        } else {
            var left = if (req.contentLength >= 0) req.contentLength else 0L
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                dest.write(buf, 0, n)
                left -= n
            }
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.join(2000) }
        acceptThread = null
        clients.clear()
        clientCount.set(0)
        startedAt = 0
    }

    override fun status(): HostedRuntimeStatus {
        val c = config
        val isUp = running.get() && serverSocket != null
        return HostedRuntimeStatus(
            kind = activeKind,
            running = isUp,
            port = c?.effectivePort ?: 0,
            listenAddresses = if (isUp) listOfNotNull(listenHint) else emptyList(),
            clientCount = clientCount.get(),
            rootPath = c?.rootPath ?: "",
            error = lastError,
            startedAtMs = startedAt,
        )
    }
}
