package com.ghostpanter.files.core.server

import com.ghostpanter.files.core.tr

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
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

/**
 * Browser-facing HTTP remote management page (list / download / upload / mkdir / delete / rename)
 * with Basic auth. Same HostedServer contract as FTP/WebDAV so the hub can drive it.
 */
class HttpRemoteServer : HostedServer {
    override val kind: HostedKind = HostedKind.HTTP
    private var config: HostedServiceConfig? = null
    private var serverSocket: ServerSocket? = null
    private var startedAt = 0L
    private var listenHint: String? = null
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<String>()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val clientCount = AtomicInteger(0)
    private var lastError: String? = null
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "ghost-http-remote").apply { isDaemon = true } }
    private var acceptThread: Thread? = null

    override fun start(config: HostedServiceConfig, listenHint: String?): HostedRuntimeStatus {
        stop()
        this.config = config
        this.listenHint = listenHint
        val root = File(config.rootPath)
        if (!root.isDirectory && !root.mkdirs()) {
            lastError = tr("共享目录不可用: ${config.rootPath}", "Shared folder unavailable: ${config.rootPath}")
            return status()
        }
        return try {
            val ss = ServerSocket(config.effectivePort)
            serverSocket = ss
            startedAt = System.currentTimeMillis()
            running.set(true)
            lastError = null
            acceptThread = Thread({
                while (running.get()) {
                    try {
                        val s = ss.accept()
                        sockets.add(s)
                        pool.execute { handle(s, root) }
                    } catch (_: Exception) {
                        if (running.get()) { /* ignore */ }
                    }
                }
            }, "ghost-http-accept").also { it.isDaemon = true; it.start() }
            status()
        } catch (e: Exception) {
            lastError = e.message
            running.set(false)
            status()
        }
    }

    override fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
        clients.clear()
        clientCount.set(0)
        serverSocket = null
        acceptThread = null
    }

    override fun status(): HostedRuntimeStatus {
        val cfg = config
        val addrs = buildList {
            listenHint?.let { add(it) }
        }
        return HostedRuntimeStatus(
            kind = HostedKind.HTTP,
            running = running.get(),
            port = cfg?.effectivePort ?: 0,
            listenAddresses = addrs,
            clientCount = clientCount.get(),
            rootPath = cfg?.rootPath.orEmpty(),
            error = lastError,
            startedAtMs = startedAt,
        )
    }

    private fun handle(socket: Socket, root: File) {
        val remote = socket.inetAddress?.hostAddress ?: "?"
        val cfg = config ?: run { socket.close(); return }
        if (cfg.lanOnly && !isLanAddress(remote)) {
            runCatching { socket.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            socket.close(); return
        }
        clients.add(remote)
        clientCount.incrementAndGet()
        try {
            socket.soTimeout = 60_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = BufferedOutputStream(socket.getOutputStream())
            val req = readRequest(input) ?: return
            if (!cfg.anonymous && !checkAuth(req, cfg)) {
                write(output, 401, "text/plain; charset=utf-8", "Unauthorized".toByteArray(),
                    extra = listOf("WWW-Authenticate: Basic realm=\"GhostFiles\""))
                return
            }
            when (req.method) {
                "GET" -> if (req.path == "/" || req.path.endsWith('/')) serveIndex(output, root, req.path)
                else serveFile(output, root, req.path)
                "POST" -> handlePost(input, output, root, req)
                "DELETE" -> {
                    val f = resolve(root, req.path)
                    if (f != null && f.exists()) {
                        if (f.isDirectory) f.deleteRecursively() else f.delete()
                        write(output, 200, "application/json", """{"ok":true}""".toByteArray())
                    } else write(output, 404, "text/plain", "not found".toByteArray())
                }
                "OPTIONS" -> write(output, 204, null, ByteArray(0), extra = listOf("Allow: GET,POST,DELETE,OPTIONS"))
                else -> write(output, 405, "text/plain", "method".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            clients.remove(remote)
            clientCount.decrementAndGet()
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private data class Req(val method: String, val path: String, val headers: Map<String, String>, val query: Map<String, String>)

    private fun readRequest(input: InputStream): Req? {
        val line = readLine(input) ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val rawPath = parts[1]
        val qIdx = rawPath.indexOf('?')
        val pathEnc = if (qIdx >= 0) rawPath.substring(0, qIdx) else rawPath
        val query = LinkedHashMap<String, String>()
        if (qIdx >= 0) {
            for (pair in rawPath.substring(qIdx + 1).split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) query[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            }
        }
        return Req(parts[0].uppercase(), urlDecode(pathEnc), headers, query)
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(StandardCharsets.UTF_8.name())
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
        }
        return buf.toString(StandardCharsets.UTF_8.name())
    }

    private fun checkAuth(req: Req, cfg: HostedServiceConfig): Boolean {
        val h = req.headers["authorization"] ?: return false
        if (!h.startsWith("Basic ", true)) return false
        val decoded = runCatching {
            String(Base64.getDecoder().decode(h.substring(6).trim()), StandardCharsets.UTF_8)
        }.getOrNull() ?: return false
        val i = decoded.indexOf(':')
        val user = if (i >= 0) decoded.substring(0, i) else decoded
        val pass = if (i >= 0) decoded.substring(i + 1) else ""
        return user == cfg.username && pass == cfg.password
    }

    private fun resolve(root: File, path: String): File? {
        val clean = path.trim('/').replace("..", "")
        val f = if (clean.isEmpty()) root else File(root, clean)
        val rootCan = root.canonicalFile
        val can = runCatching { f.canonicalFile }.getOrNull() ?: return null
        if (!can.path.startsWith(rootCan.path)) return null
        return can
    }

    private fun serveIndex(out: OutputStream, root: File, path: String) {
        val dir = resolve(root, path) ?: run {
            write(out, 404, "text/plain", "not found".toByteArray()); return
        }
        if (!dir.isDirectory) { serveFile(out, root, path); return }
        val rel = root.toURI().relativize(dir.toURI()).path.trimEnd('/')
        val children = dir.listFiles()?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }).orEmpty()
        val rows = children.joinToString("") { f ->
            val name = f.name + if (f.isDirectory) "/" else ""
            val href = urlEncodePath((if (rel.isEmpty()) name else "$rel/$name"))
            val size = if (f.isDirectory) "-" else formatSize(f.length())
            val mtime = sdf.format(Date(f.lastModified()))
            """<tr><td><a href="/$href">${escape(name)}</a></td><td>$size</td><td>$mtime</td>
<td><form style="display:inline" method="post" action="/?op=delete&path=${urlEncode(if (rel.isEmpty()) f.name else "$rel/${f.name}")}" onsubmit="return confirm('删除？')"><button type="submit">删</button></form></td></tr>"""
        }
        val up = if (rel.isEmpty()) "" else {
            val parent = rel.substringBeforeLast('/', "")
            """<p><a href="/${urlEncodePath(parent)}${if (parent.isEmpty()) "" else "/"}">.. 上级</a></p>"""
        }
        val html = """<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>双栏文件管理器 · 远程管理</title>
<style>
body{font-family:system-ui,sans-serif;margin:16px;background:#0f1419;color:#e7ecf1}
a{color:#6cb6ff} table{border-collapse:collapse;width:100%} td,th{padding:6px 8px;border-bottom:1px solid #243044;text-align:left;font-size:14px}
button,input,select{background:#1b2430;color:#e7ecf1;border:1px solid #334155;border-radius:6px;padding:6px 10px}
.card{background:#161b22;border:1px solid #243044;border-radius:10px;padding:12px;margin-bottom:12px}
</style></head><body>
<h2>双栏文件管理器 · 远程管理</h2>
<div class="card">路径：/${escape(rel.ifEmpty { "" })}</div>
$up
<div class="card">
<form method="post" action="/?op=mkdir" style="display:flex;gap:8px;flex-wrap:wrap;margin-bottom:8px">
<input type="hidden" name="dir" value="${escape(rel)}"/>
<input name="name" placeholder="新建文件夹名" required/>
<button type="submit">新建文件夹</button>
</form>
<form method="post" action="/?op=upload&dir=${urlEncode(rel)}" enctype="multipart/form-data" style="display:flex;gap:8px;flex-wrap:wrap">
<input type="file" name="file" required/>
<button type="submit">上传文件</button>
</form>
</div>
<table><tr><th>名称</th><th>大小</th><th>修改时间</th><th></th></tr>$rows</table>
</body></html>"""
        write(out, 200, "text/html; charset=utf-8", html.toByteArray(StandardCharsets.UTF_8))
    }

    private fun serveFile(out: OutputStream, root: File, path: String) {
        val f = resolve(root, path)
        if (f == null || !f.isFile) { write(out, 404, "text/plain", "not found".toByteArray()); return }
        val bytes = f.readBytes()
        val mime = when (f.extension.lowercase()) {
            "html", "htm" -> "text/html; charset=utf-8"
            "txt", "log", "md" -> "text/plain; charset=utf-8"
            "json" -> "application/json"
            "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"
            "mp4" -> "video/mp4"; "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
        write(out, 200, mime, bytes, extra = listOf(
            "Content-Disposition: attachment; filename=\"${f.name.replace("\"", "")}\"",
        ))
    }

    private fun handlePost(input: InputStream, out: OutputStream, root: File, req: Req) {
        val op = req.query["op"] ?: ""
        val cl = req.headers["content-length"]?.toLongOrNull() ?: 0L
        val ctype = req.headers["content-type"].orEmpty()
        when (op) {
            "mkdir" -> {
                val body = readBody(input, cl, ctype)
                val name = body["name"]?.trim().orEmpty()
                val dir = body["dir"].orEmpty()
                if (name.isEmpty() || name.contains('/') || name.contains("..")) {
                    write(out, 400, "text/plain", "bad name".toByteArray()); return
                }
                val parent = resolve(root, dir) ?: root
                File(parent, name).mkdirs()
                redirect(out, "/${urlEncodePath(dir)}${if (dir.isEmpty()) "" else "/"}")
            }
            "delete" -> {
                val path = req.query["path"].orEmpty()
                val f = resolve(root, path)
                if (f != null && f.exists()) {
                    if (f.isDirectory) f.deleteRecursively() else f.delete()
                }
                val parent = path.substringBeforeLast('/', "")
                redirect(out, "/${urlEncodePath(parent)}${if (parent.isEmpty()) "" else "/"}")
            }
            "upload" -> {
                if (!ctype.startsWith("multipart/form-data")) {
                    write(out, 400, "text/plain", "need multipart".toByteArray()); return
                }
                val boundary = ctype.substringAfter("boundary=", "").trim()
                if (boundary.isEmpty()) { write(out, 400, "text/plain", "no boundary".toByteArray()); return }
                val dir = req.query["dir"].orEmpty()
                val parent = resolve(root, dir) ?: root
                saveMultipartFile(input, boundary, cl, parent)
                redirect(out, "/${urlEncodePath(dir)}${if (dir.isEmpty()) "" else "/"}")
            }
            else -> write(out, 400, "text/plain", "unknown op".toByteArray())
        }
    }

    private fun readBody(input: InputStream, cl: Long, ctype: String): Map<String, String> {
        if (cl <= 0 || cl > 1_000_000) return emptyMap()
        val buf = ByteArray(cl.toInt())
        var n = 0
        while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
        val raw = String(buf, 0, n, StandardCharsets.UTF_8)
        if (ctype.contains("application/x-www-form-urlencoded") || !ctype.contains("multipart")) {
            return raw.split('&').mapNotNull {
                val i = it.indexOf('='); if (i < 0) null else urlDecode(it.substring(0, i)) to urlDecode(it.substring(i + 1))
            }.toMap()
        }
        return emptyMap()
    }

    private fun saveMultipartFile(input: InputStream, boundary: String, cl: Long, parent: File) {
        // Stream until filename= then write until boundary
        val marker = "--$boundary"
        val all = ByteArray(cl.toInt().coerceAtMost(512 * 1024 * 1024))
        var n = 0
        while (n < all.size) { val r = input.read(all, n, all.size - n); if (r < 0) break; n += r }
        val data = all.copyOf(n)
        val textHead = String(data, 0, minOf(data.size, 8192), StandardCharsets.ISO_8859_1)
        val fnKey = "filename=\""
        val fi = textHead.indexOf(fnKey)
        if (fi < 0) return
        val name = textHead.substring(fi + fnKey.length).substringBefore('"').substringAfterLast('/').substringAfterLast('\\')
        if (name.isEmpty() || name.contains("..")) return
        val headerEnd = indexOf(data, "\r\n\r\n".toByteArray())
        if (headerEnd < 0) return
        val start = headerEnd + 4
        val endMarker = ("\r\n--$boundary").toByteArray(StandardCharsets.ISO_8859_1)
        var end = indexOf(data, endMarker, start)
        if (end < 0) end = data.size
        val out = File(parent, name)
        out.outputStream().use { it.write(data, start, end - start) }
    }

    private fun indexOf(data: ByteArray, pat: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..data.size - pat.size) {
            for (j in pat.indices) if (data[i + j] != pat[j]) continue@outer
            return i
        }
        return -1
    }

    private fun redirect(out: OutputStream, loc: String) {
        write(out, 302, null, ByteArray(0), extra = listOf("Location: $loc"))
    }

    private fun write(out: OutputStream, code: Int, contentType: String?, body: ByteArray, extra: List<String> = emptyList()) {
        val reason = when (code) {
            200 -> "OK"; 204 -> "No Content"; 302 -> "Found"; 401 -> "Unauthorized"
            403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error"
        }
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $code $reason\r\n")
        sb.append("Connection: close\r\n")
        sb.append("Cache-Control: no-store\r\n")
        if (contentType != null) sb.append("Content-Type: $contentType\r\n")
        sb.append("Content-Length: ${body.size}\r\n")
        for (e in extra) sb.append(e).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun urlDecode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun urlEncodePath(s: String) = s.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = TimeZone.getDefault() }
    private fun formatSize(b: Long): String {
        if (b < 1024) return "$b B"
        val u = arrayOf("KB", "MB", "GB", "TB"); var v = b / 1024.0; var i = 0
        while (v >= 1024 && i < u.lastIndex) { v /= 1024; i++ }
        return String.format(Locale.US, "%.1f %s", v, u[i])
    }
}
