package com.ghostpanter.files.core

import com.ghostpanter.files.core.net.OneShotBody
import com.ghostpanter.files.core.net.RedirectRouteCache
import com.ghostpanter.files.core.net.SharedRedirectRoutes
import com.ghostpanter.files.core.net.WebDavRedirectInterceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import okio.BufferedSink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Minimal WebDAV server (class 1) backed by a temp directory, enough to exercise the client. */
class MiniDav(private val root: File, private val prefix: String = "/dav") : Dispatcher() {
    val methods = java.util.concurrent.CopyOnWriteArrayList<String>()
    private fun fileOf(rawPath: String): File? {
        val p = URLDecoder.decode(rawPath.replace("+", "%2B"), "UTF-8")
        if (!p.startsWith(prefix)) return null
        return File(root, p.removePrefix(prefix).trim('/'))
    }
    private fun href(f: File): String {
        val rel = f.relativeTo(root).invariantSeparatorsPath
        val enc = rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return "$prefix/$enc" + if (f.isDirectory) "/" else ""
    }
    private val fmt = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }
    private fun prop(f: File) = "<d:response><d:href>${href(f)}</d:href><d:propstat><d:prop>" +
        (if (f.isDirectory) "<d:resourcetype><d:collection/></d:resourcetype>" else "<d:resourcetype/><d:getcontentlength>${f.length()}</d:getcontentlength>") +
        "<d:getlastmodified>${fmt.format(Date(f.lastModified()))}</d:getlastmodified></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"

    override fun dispatch(request: RecordedRequest): MockResponse {
        methods.add(request.method!!)
        val f = fileOf(request.requestUrl!!.encodedPath) ?: return MockResponse().setResponseCode(404)
        fun dest(): File? = request.getHeader("Destination")?.let { fileOf(URI(it).rawPath) }
        return when (request.method) {
            "PROPFIND" -> {
                if (!f.exists()) return MockResponse().setResponseCode(404)
                val depth = request.getHeader("Depth") ?: "1"
                val items = listOf(f) + if (depth == "1" && f.isDirectory) f.listFiles()!!.toList() else emptyList()
                MockResponse().setResponseCode(207).setHeader("Content-Type", "application/xml")
                    .setBody("<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">" + items.joinToString("") { prop(it) } + "</d:multistatus>")
            }
            "GET" -> {
                if (!f.isFile) return MockResponse().setResponseCode(404)
                val range = request.getHeader("Range")
                val bytes = f.readBytes()
                if (range != null) {
                    val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                    MockResponse().setResponseCode(206).setBody(Buffer().write(bytes.copyOfRange(start, bytes.size)))
                } else MockResponse().setBody(Buffer().write(bytes))
            }
            "PUT" -> {
                if (!f.parentFile.isDirectory) return MockResponse().setResponseCode(409)
                f.writeBytes(request.body.readByteArray()); MockResponse().setResponseCode(201)
            }
            "PATCH" -> {
                if (!f.parentFile.isDirectory) return MockResponse().setResponseCode(409)
                val range = request.getHeader("X-Update-Range") ?: request.getHeader("Content-Range") ?: return MockResponse().setResponseCode(400)
                val offset = range.substringAfter("bytes=").substringAfter("bytes ").substringBefore('-').toLongOrNull()
                    ?: return MockResponse().setResponseCode(400)
                val chunk = request.body.readByteArray()
                val raf = java.io.RandomAccessFile(f, "rw")
                raf.seek(offset)
                raf.write(chunk)
                raf.setLength(offset + chunk.size)
                raf.close()
                MockResponse().setResponseCode(204)
            }
            "MKCOL" -> if (f.exists()) MockResponse().setResponseCode(405) else { f.mkdir(); MockResponse().setResponseCode(201) }
            "DELETE" -> if (!f.exists()) MockResponse().setResponseCode(404) else { f.deleteRecursively(); MockResponse().setResponseCode(204) }
            "MOVE", "COPY" -> {
                val d = dest() ?: return MockResponse().setResponseCode(400)
                if (d.exists() && request.getHeader("Overwrite") == "F") return MockResponse().setResponseCode(412)
                if (request.method == "MOVE") { d.deleteRecursively(); check(f.renameTo(d)) } else { d.deleteRecursively(); f.copyRecursively(d) }
                MockResponse().setResponseCode(201)
            }
            else -> MockResponse().setResponseCode(405)
        }
    }
}

class WebDavFsTest {
    private val server = MockWebServer()
    private val root = tempDir("dav")
    private val dav = MiniDav(root)

    @Before fun start() {
        server.dispatcher = dav
        server.start()
        // Hops are process-wide now, so every test must start from a clean slate (ports get reused).
        SharedRedirectRoutes.cache.clear()
    }
    @After fun stop() { runCatching { server.shutdown() } }

    private fun fs() = WebDavFs(
        ServerConfig("w", "dav", Protocol.WEBDAV, server.hostName, server.port, "u", "p", "/dav"),
    )

    @Test fun contract() = fs().use { FsContract.run(it, "/") }

    @Test fun sameServerCopyAndMoveUseDavMethods() {
        File(root, "a").mkdirs()
        File(root, "a/x.txt").writeText("hello")
        val f = fs()
        assertTrue(f.serverCopy("/a/x.txt", "/a/y.txt"))
        assertEquals("hello", File(root, "a/y.txt").readText())
        f.rename("/a/y.txt", "/z.txt")
        assertTrue(File(root, "z.txt").exists())
        assertTrue("COPY" in dav.methods && "MOVE" in dav.methods)
    }

    @Test fun sendsBasicAuth() {
        fs().list("/")
        val req = server.takeRequest()
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    // ---- redirects: public gateway 307 -> real backend (cross origin, so OkHttp would strip auth) ----

    /** Front end that redirects every request to [backend], like the SNI gateway in production. */
    private fun gatewayTo(backend: MockWebServer, code: Int = 307): MockWebServer {
        val gw = MockWebServer()
        gw.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(code)
                    .setHeader("Location", backend.url(request.requestUrl!!.encodedPath).toString())
        }
        gw.start()
        return gw
    }

    /** Records what the backend actually received, then delegates to [MiniDav]. */
    private fun recording(out: MutableList<RecordedRequest>): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            out.add(request)
            return dav.dispatch(request)
        }
    }

    @Test fun keepsBasicAuthAndMethodAcross307() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = recording(seen)
        val gw = gatewayTo(server)
        val f = WebDavFs(ServerConfig("w", "dav", Protocol.WEBDAV, gw.hostName, gw.port, "u", "p", "/dav"))
        try {
            assertTrue("跳转后的 PROPFIND 不应 401", f.list("/").isEmpty())

            // Streaming PUT: the hop must be resolved up front, otherwise the body cannot be replayed.
            f.openWrite("/up.txt", 0, -1).use { it.write("hello".toByteArray()) }
            assertEquals("hello", File(root, "up.txt").readText())

            // GET is replayable, so it may follow the hop inline.
            assertEquals("hello", f.openRead("/up.txt", 0).use { String(it.readBytes()) })

            assertTrue("后端没有收到任何请求", seen.isNotEmpty())
            assertTrue(
                "跳转后丢失了 Authorization: " + seen.map { "${it.method} ${it.getHeader("Authorization")}" },
                seen.all { it.getHeader("Authorization")?.startsWith("Basic ") == true },
            )
            assertTrue("PUT 必须原样保留方法名", seen.any { it.method == "PUT" })
        } finally {
            gw.shutdown()
            f.close()
            server.dispatcher = dav
        }
    }

    @Test fun doesNotDowngradePutToGetOn302() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        server.dispatcher = recording(seen)
        val gw = gatewayTo(server, 302)
        val f = WebDavFs(ServerConfig("w", "dav", Protocol.WEBDAV, gw.hostName, gw.port, "u", "p", "/dav"))
        try {
            f.openWrite("/x.txt", 0, -1).use { it.write("abc".toByteArray()) }
            assertEquals("abc", File(root, "x.txt").readText())
            assertTrue(
                "PUT 不能被降级成 GET（服务器会回 200 但文件没写进去）: " + seen.map { it.method },
                seen.none { it.method == "GET" },
            )
        } finally {
            gw.shutdown()
            f.close()
            server.dispatcher = dav
        }
    }

    @Test fun oneShotBodyFailsFastInsteadOfHanging() {
        val gw = gatewayTo(server)
        val cache = RedirectRouteCache()
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(WebDavRedirectInterceptor(routeCache = cache))
            .build()
        val body = object : RequestBody(), OneShotBody {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) { sink.writeUtf8("x") }
        }
        try {
            val e = runCatching {
                client.newCall(Request.Builder().url(gw.url("/dav/x.txt")).put(body).build()).execute().close()
            }.exceptionOrNull()
            assertTrue("应快速失败而不是卡在上传队列上: $e", e is IOException && e.message!!.contains("无法重放"))
            assertNotNull("跳转地址必须被缓存，供重试时直连", cache.resolve(gw.url("/dav/x.txt")))
        } finally {
            gw.shutdown()
        }
    }

    /**
     * The point of caching the hop: once the first 30x has been seen, requests must stop paying a
     * gateway round trip.
     */
    @Test fun cachedHopSkipsGatewayOnSubsequentRequests() {
        server.dispatcher = recording(java.util.concurrent.CopyOnWriteArrayList())
        val gatewayHits = java.util.concurrent.atomic.AtomicInteger()
        val gw = MockWebServer()
        gw.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gatewayHits.incrementAndGet()
                return MockResponse().setResponseCode(307)
                    .setHeader("Location", server.url(request.requestUrl!!.encodedPath).toString())
            }
        }
        gw.start()
        val f = WebDavFs(ServerConfig("w", "dav", Protocol.WEBDAV, gw.hostName, gw.port, "u", "p", "/dav"))
        try {
            repeat(3) { f.list("/") }
            assertEquals("学到跳转后应直连后端，网关只该被访问一次", 1, gatewayHits.get())
        } finally {
            gw.shutdown()
            f.close()
            server.dispatcher = dav
        }
    }

    /**
     * Transfers open their own dedicated client (`FsManager.openDedicated`), so the learned hop has
     * to be shared process-wide — otherwise every transfer re-learns it with an extra round trip.
     */
    @Test fun dedicatedClientsShareLearnedHop() {
        server.dispatcher = recording(java.util.concurrent.CopyOnWriteArrayList())
        val gatewayHits = java.util.concurrent.atomic.AtomicInteger()
        val gw = MockWebServer()
        gw.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                gatewayHits.incrementAndGet()
                return MockResponse().setResponseCode(307)
                    .setHeader("Location", server.url(request.requestUrl!!.encodedPath).toString())
            }
        }
        gw.start()
        val cfg = ServerConfig("w", "dav", Protocol.WEBDAV, gw.hostName, gw.port, "u", "p", "/dav")
        val browse = WebDavFs(cfg)
        val job = WebDavFs(cfg)   // stands in for a dedicated transfer connection
        try {
            browse.list("/")                       // learns the hop
            job.list("/")                          // separate client, same process: must go direct
            assertEquals("专用连接应复用已学到的跳转，不再经过网关", 1, gatewayHits.get())
        } finally {
            gw.shutdown()
            browse.close()
            job.close()
            server.dispatcher = dav
        }
    }

    /**
     * The safety net behind the shortcut: when the learned backend has moved, the request must fall
     * back to the gateway instead of failing outright, and still carry credentials.
     */
    @Test fun staleHopFallsBackToGateway() {
        val newBackend = MockWebServer()
        val seenNew = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
        newBackend.dispatcher = recording(seenNew)
        newBackend.start()

        val target = java.util.concurrent.atomic.AtomicReference(server)
        val gw = MockWebServer()
        gw.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(307)
                    .setHeader("Location", target.get().url(request.requestUrl!!.encodedPath).toString())
        }
        gw.start()

        val f = WebDavFs(ServerConfig("w", "dav", Protocol.WEBDAV, gw.hostName, gw.port, "u", "p", "/dav"))
        try {
            f.list("/")                        // learns: gateway -> old backend
            server.shutdown()                  // old backend goes away
            target.set(newBackend)             // gateway now points at the new backend
            f.list("/")                        // must fall back through the gateway and succeed
            assertTrue("直连失败后应回退到网关并打到新后端", seenNew.isNotEmpty())
            assertTrue(
                "回退后仍须带上 Authorization: " + seenNew.map { it.method to it.getHeader("Authorization") },
                seenNew.all { it.getHeader("Authorization")?.startsWith("Basic ") == true },
            )
        } finally {
            gw.shutdown()
            newBackend.shutdown()
            f.close()
            server.dispatcher = dav
        }
    }
}
