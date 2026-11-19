package com.ghostpanter.files.core

import com.ghostpanter.files.core.net.BodyNotReplayableException
import com.ghostpanter.files.core.net.LanSocketBinder
import com.ghostpanter.files.core.net.NetLog
import com.ghostpanter.files.core.net.OneShotBody
import com.ghostpanter.files.core.net.RedirectRouteCache
import com.ghostpanter.files.core.net.SharedRedirectRoutes
import com.ghostpanter.files.core.net.TooManyRedirectsException
import com.ghostpanter.files.core.net.WebDavRedirectInterceptor
import com.ghostpanter.files.core.net.retarget
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException
import javax.xml.parsers.SAXParserFactory

/**
 * WebDAV over OkHttp (pooled keep-alive connections, explicit timeouts).
 * Server-side MOVE/COPY is used for same-server moves and copies; downloads
 * resume with HTTP Range. Uploads stream through a bounded queue so memory
 * stays flat for multi-GB files.
 */
class WebDavFs(
    private val config: ServerConfig,
    private val timeouts: NetTimeouts = NetTimeouts(),
    baseUrlOverride: String? = null,
    /**
     * Hops learned from the gateway. Shared process-wide by default: browsing reuses one client, but
     * every transfer job opens a dedicated connection, and a cold cache there would make each
     * transfer re-learn the hop with an extra round trip.
     */
    private val redirects: RedirectRouteCache = SharedRedirectRoutes.cache,
) : VirtualFs {

    override val id = config.fsId
    override val displayName = config.name
    override val capabilities = setOf(
        Capability.RESUME_READ, Capability.RESUME_WRITE, Capability.SERVER_COPY, Capability.RENAME_MOVE, Capability.REMOTE,
    )

    private val base: HttpUrl = (baseUrlOverride ?: run {
        val scheme = if (config.protocol == Protocol.WEBDAVS) "https" else "http"
        val bp = "/" + config.path.trim().trim('/')
        "$scheme://${config.host}:${config.effectivePort}${if (bp == "/") "/" else "$bp/"}"
    }).toHttpUrl()

    private val basePath: String = base.encodedPath.let { decode(it) }.trimEnd('/')

    private val redirectsInterceptor = WebDavRedirectInterceptor(
        routeCache = redirects,
        username = config.username,
        password = config.password,
    )

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeouts.connectMs.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(timeouts.readMs.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(timeouts.readMs.toLong(), TimeUnit.MILLISECONDS)
        .connectionPool(ConnectionPool(5, 2, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        // Redirects are handled by redirectsInterceptor: OkHttp's own follow-up drops
        // Authorization on a cross-origin hop and would 401 every redirected PUT.
        .followRedirects(false)
        .followSslRedirects(false)
        // Dedicated client: bind LAN sockets to Wi‑Fi when a VPN is up (host known from config).
        .socketFactory(LanSocketBinder.Factory(timeouts.connectMs, defaultHost = config.host))
        // Order matters: auth runs first so the redirect interceptor sees (and copies) the header.
        .addInterceptor { chain ->
            val req = chain.request()
            if (config.username.isNotEmpty() && !config.anonymous) {
                chain.proceed(req.newBuilder().header("Authorization", Credentials.basic(config.username, config.password, Charsets.UTF_8)).build())
            } else chain.proceed(req)
        }
        .addInterceptor(redirectsInterceptor)
        .build()

    private fun url(path: String, dir: Boolean = false): HttpUrl {
        val b = base.newBuilder()
        // drop trailing empty segment of base
        val segs = VPath.normalize(path).trim('/').split('/').filter { it.isNotEmpty() }
        if (base.pathSegments.lastOrNull() == "") b.removePathSegment(base.pathSegments.size - 1)
        for (s in segs) b.addPathSegment(s)
        if (dir) b.addPathSegment("")
        return b.build()
    }

    /**
     * Sends [req], preferring a backend origin learned from an earlier 30x hop.
     *
     * Every request used to pay a full gateway round trip before the hop could be followed; once the
     * hop is known the request goes straight to the backend. If that direct attempt fails at the
     * connection level the hop may be stale (backend moved), so it is forgotten and the gateway gets
     * one retry — the shortcut must never turn a moved backend into a hard failure.
     */
    private fun call(req: Request): Response {
        val target = redirects.resolve(req.url)
        if (target == null) return attempt(req)

        val direct = retarget(req, target)
        NetLog.d { "direct ${req.method} ${req.url.host} -> ${target.host} (cached hop)" }
        return try {
            http.newCall(direct).execute()
        } catch (e: IOException) {
            // A one-shot body has already been drained, so re-sending it would block forever on an
            // empty queue: fail here and let the caller retry with a fresh body instead.
            val replayable = req.body == null || req.body !is OneShotBody
            if (!replayable || !isHopFailure(e)) {
                // Forget the hop anyway, so a retry with a fresh body consults the gateway again.
                redirects.invalidate(req.url)
                throw wrap(direct, e)
            }
            NetLog.w { "!! 直连 ${direct.url.host} 失败（${e.javaClass.simpleName}），丢弃该跳转并用网关重试" }
            redirects.invalidate(req.url)
            attempt(req)
        }
    }

    /** One attempt from the configured gateway URL. */
    private fun attempt(req: Request): Response = try {
        http.newCall(req).execute()
    } catch (e: IOException) {
        throw wrap(req, e)
    }

    /** Maps raw I/O failures onto a message that names the method and path. */
    private fun wrap(req: Request, e: IOException): FsException = when (e) {
        is SSLPeerUnverifiedException -> FsException(
            tr(
                "${req.method} ${req.url.host} TLS 证书与域名不匹配（跳转目标很可能用的是网关证书）: ${e.message}",
                "${req.method} ${req.url.host} TLS certificate does not match host (redirect target likely holds the gateway cert): ${e.message}",
            ),
            e,
        )
        else -> FsException(
            "${req.method} ${VPath.normalize(decode(req.url.encodedPath))} " + tr("失败", "failed") + ": ${e.message}",
            e,
        )
    }

    /**
     * Whether the failure on a direct attempt is worth one retry through the gateway.
     *
     * Anything connection-level qualifies, including the `unexpected end of stream` a stale
     * keep-alive connection produces. The two redirect-chain errors do not: one already exhausted
     * `maxHops` and the other needs a fresh body from the caller.
     */
    private fun isHopFailure(e: IOException): Boolean =
        e !is TooManyRedirectsException && e !is BodyNotReplayableException

    /**
     * Resolves the gateway hop for a streaming upload before the body is created.
     *
     * A one-shot body cannot be re-sent, so a 307 in the middle of a PUT would either hang on the
     * drained queue or force a full retry. One cheap OPTIONS is enough: the redirect interceptor
     * follows it and records the mapping, then the PUT goes straight to the backend.
     */
    private fun resolveUploadUrl(u: HttpUrl): HttpUrl {
        redirects.resolve(u)?.let {
            NetLog.d { "upload route (cached): ${u.host} -> ${it.host}" }
            return it
        }
        runCatching {
            http.newCall(Request.Builder().url(u).method("OPTIONS", null).build()).execute().use { }
        }
        val resolved = redirects.resolve(u)
        NetLog.d { "upload route: ${u.host} -> ${resolved?.host ?: "(direct)"}" }
        return resolved ?: u
    }

    private fun fail(r: Response, what: String): Nothing {
        val code = r.code
        r.close()
        throw FsException("$what: HTTP $code" + when (code) {
            401 -> tr(" 认证失败", " authentication failed")
            403 -> tr(" 无权限", " permission denied")
            404 -> tr(" 不存在", " not found")
            405 -> tr(" 不允许", " not allowed")
            409 -> tr(" 父目录不存在", " parent not found")
            412 -> tr(" 目标已存在", " target exists")
            507 -> tr(" 空间不足", " insufficient storage")
            else -> ""
        })
    }

    private fun propfind(path: String, depth: Int): Pair<Int, List<FileEntry>> {
        val req = Request.Builder().url(url(path, dir = depth == 1))
            .method("PROPFIND", PROPFIND_BODY.toRequestBody(XML))
            .header("Depth", depth.toString())
            .build()
        call(req).use { r ->
            if (r.code == 404) return 404 to emptyList()
            if (r.code != 207 && r.code != 200) fail(r, tr("列出 $path", "List $path"))
            val entries = parseMultiStatus(r.body!!.byteStream())
            return r.code to entries
        }
    }

    private fun parseMultiStatus(input: InputStream): List<FileEntry> {
        val out = ArrayList<FileEntry>()
        val f = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        f.newSAXParser().parse(input, object : DefaultHandler() {
            var href: String? = null
            var isDir = false
            var size = 0L
            var mtime = 0L
            var okStatus = true
            val text = StringBuilder()
            var inResponse = false
            override fun startElement(uri: String?, local: String, q: String?, attrs: Attributes?) {
                text.setLength(0)
                when (local) {
                    "response" -> { inResponse = true; href = null; isDir = false; size = 0; mtime = 0 }
                    "collection" -> if (inResponse) isDir = true
                }
            }
            override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
            override fun endElement(uri: String?, local: String, q: String?) {
                val t = text.toString().trim()
                when (local) {
                    "href" -> if (inResponse && href == null) href = t
                    "getcontentlength" -> size = t.toLongOrNull() ?: 0
                    "getlastmodified" -> mtime = parseDate(t)
                    "response" -> {
                        inResponse = false
                        val h = href ?: return
                        val p = hrefToPath(h)
                        if (p != null) out.add(FileEntry(p, VPath.name(p), isDir, if (isDir) 0 else size, mtime))
                    }
                }
                text.setLength(0)
            }
        })
        return out
    }

    private fun hrefToPath(href: String): String? {
        val rawPath = runCatching { URI(href).rawPath }.getOrNull() ?: href
        val decoded = decode(rawPath).trimEnd('/')
        if (basePath.isNotEmpty() && decoded != basePath && !decoded.startsWith("$basePath/")) return null
        return VPath.normalize(decoded.removePrefix(basePath))
    }

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> {
        val dir = VPath.normalize(path)
        val (code, entries) = propfind(dir, 1)
        if (code == 404) throw FsException(tr("目录不存在: $dir", "Directory not found: $dir"))
        val out = entries.filter { it.path != dir }
        onChunk?.invoke(out)
        return out
    }

    override fun stat(path: String): FileEntry? {
        val p = VPath.normalize(path)
        if (p == VPath.ROOT) return FileEntry(p, "", true, 0, 0)
        val (code, entries) = propfind(p, 0)
        if (code == 404) return null
        return entries.firstOrNull()?.copy(path = p, name = VPath.name(p))
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val b = Request.Builder().url(url(path)).get()
        if (offset > 0) b.header("Range", "bytes=$offset-")
        val r = call(b.build())
        if (!r.isSuccessful) fail(r, tr("下载 $path", "Download $path"))
        val body = r.body!!.byteStream()
        if (offset > 0 && r.code == 200) {
            // Server ignored Range: skip manually.
            var left = offset
            while (left > 0) {
                val n = body.skip(left)
                if (n <= 0) { if (body.read() < 0) break else left-- } else left -= n
            }
        }
        return object : FilterInputStream(body) {
            override fun close() { runCatching { super.close() }; r.close() }
        }
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream {
        val pipe = ArrayBlockingQueue<ByteArray>(8)
        val eofMarker = ByteArray(0)
        val failureRef = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val responseRef = java.util.concurrent.atomic.AtomicReference<Response?>()
        val finished = CountDownLatch(1)
        // Remaining bytes after [offset]. sizeHint is total final size when known.
        val remainingHint = if (sizeHint >= 0 && offset > 0) (sizeHint - offset).coerceAtLeast(0) else if (offset == 0L) sizeHint else -1L
        val body = object : RequestBody(), OneShotBody {
            override fun contentType() = OCTET
            override fun contentLength() = remainingHint
            override fun writeTo(sink: BufferedSink) {
                while (true) {
                    val chunk = pipe.poll(timeouts.readMs.toLong() * 2, TimeUnit.MILLISECONDS)
                        ?: throw IOException(tr("上传数据源停滞", "Upload source stalled"))
                    if (chunk === eofMarker) break
                    sink.write(chunk)
                }
            }
        }
        // offset==0: normal PUT. offset>0: SabreDAV/Nextcloud PATCH with X-Update-Range.
        // A streaming body can never be replayed, so the gateway hop is resolved up front and the
        // upload is aimed at the backend directly (see resolveUploadUrl).
        val target = resolveUploadUrl(url(path))
        val req = if (offset > 0) {
            Request.Builder().url(target)
                .patch(body)
                .header("X-Update-Range", "bytes=$offset-")
                .header("Content-Range", "bytes $offset-/*")
                .build()
        } else {
            Request.Builder().url(target).put(body).build()
        }
        val worker = Thread({
            try {
                responseRef.set(http.newCall(req).execute())
            } catch (t: Throwable) {
                failureRef.set(t)
                pipe.clear()
            } finally {
                finished.countDown()
            }
        }, if (offset > 0) "webdav-patch" else "webdav-put").apply { isDaemon = true; start() }

        return object : OutputStream() {
            private var closed = false
            private fun offer(b: ByteArray) {
                while (true) {
                    failureRef.get()?.let { throw FsException(tr("上传失败: ${it.message}", "Upload failed: ${it.message}"), it) }
                    if (finished.count == 0L) {
                        responseRef.get()?.let { if (!it.isSuccessful) fail(it, tr("上传 $path", "Upload $path")) }
                        if (b === eofMarker) return
                        throw FsException(tr("服务器提前结束上传", "Server ended upload early"))
                    }
                    if (pipe.offer(b, 1, TimeUnit.SECONDS)) return
                    if (!worker.isAlive) throw FsException(tr("上传线程已终止", "Upload thread died"))
                }
            }
            override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (len > 0) offer(b.copyOfRange(off, off + len))
            }
            override fun close() {
                if (closed) return
                closed = true
                offer(eofMarker)
                if (!finished.await(timeouts.readMs.toLong() * 2, TimeUnit.MILLISECONDS)) {
                    throw FsException(tr("等待服务器确认上传超时", "Timed out waiting for server to confirm upload"))
                }
                failureRef.get()?.let { throw FsException(tr("上传失败: ${it.message}", "Upload failed: ${it.message}"), it) }
                val r = responseRef.get() ?: throw FsException(tr("上传无响应", "No response to upload"))
                if (!r.isSuccessful) fail(r, tr("上传 $path", "Upload $path"))
                r.close()
            }
        }
    }

    override fun mkdirs(path: String) {
        var cur = ""
        for (part in VPath.normalize(path).trim('/').split('/').filter { it.isNotEmpty() }) {
            cur = "$cur/$part"
            val r = call(Request.Builder().url(url(cur, dir = true)).method("MKCOL", null).build())
            // 405 = already exists
            if (!r.isSuccessful && r.code != 405) fail(r, tr("创建目录 $cur", "Create directory $cur"))
            r.close()
        }
    }

    override fun delete(path: String, recursive: Boolean) {
        val r = call(Request.Builder().url(url(path)).delete().build())
        if (!r.isSuccessful && r.code != 404) fail(r, tr("删除 $path", "Delete $path"))
        r.close()
    }

    private fun moveOrCopy(method: String, from: String, to: String, overwrite: Boolean): Response {
        val isDir = stat(from)?.isDirectory == true
        return call(
            Request.Builder().url(url(from, isDir)).method(method, null)
                .header("Destination", url(to, isDir).toString())
                .header("Overwrite", if (overwrite) "T" else "F")
                .header("Depth", "infinity")
                .build()
        )
    }

    override fun rename(from: String, to: String) {
        val r = moveOrCopy("MOVE", from, to, false)
        if (!r.isSuccessful) fail(r, tr("移动 $from", "Move $from"))
        r.close()
    }

    override fun serverCopy(from: String, to: String): Boolean = try {
        moveOrCopy("COPY", from, to, true).use { it.isSuccessful }
    } catch (e: IOException) { false }

    override fun reset() {
        // Drop learned gateway hops too: they may point at a backend that moved.
        redirects.clear()
        http.connectionPool.evictAll()
    }

    override fun close() {
        http.connectionPool.evictAll()
        http.dispatcher.executorService.shutdown()
    }

    companion object {
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8"?>
<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""

        fun decode(s: String): String = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

        private val dateFormats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        )

        fun parseDate(s: String): Long {
            for (f in dateFormats) {
                val p = runCatching {
                    SimpleDateFormat(f, Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.parse(s)?.time
                }.getOrNull()
                if (p != null) return p
            }
            return 0
        }
    }
}
