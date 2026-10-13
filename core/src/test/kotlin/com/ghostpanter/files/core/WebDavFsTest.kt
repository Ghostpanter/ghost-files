package com.ghostpanter.files.core

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
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

    @Before fun start() { server.dispatcher = dav; server.start() }
    @After fun stop() { server.shutdown() }

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
}
