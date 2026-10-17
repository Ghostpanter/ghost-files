package com.ghostpanter.files.core.server

import com.ghostpanter.files.core.VirtualFs
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Loopback-only HTTP server that streams one [VirtualFs] file per random token, with Range support, so a media
 * player (libVLC) can seek inside files that live on Shizuku/Root/SAF/SMB/SFTP/WebDAV/FTP without copying them.
 */
class FsStreamProxy : Closeable {
    private class Item(val fs: VirtualFs, val path: String, val size: Long, val mime: String)

    private val items = ConcurrentHashMap<String, Item>()
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        thread(isDaemon = true, name = "fs-proxy") {
            while (!server.isClosed) {
                val s = try { server.accept() } catch (_: SocketException) { break }
                thread(isDaemon = true, name = "fs-proxy-conn") { runCatching { serve(s) }; runCatching { s.close() } }
            }
        }
    }

    /** Returns a http://127.0.0.1 URL for [path] on [fs]. */
    fun register(fs: VirtualFs, path: String, size: Long, mime: String = "application/octet-stream"): String {
        val token = UUID.randomUUID().toString().replace("-", "")
        items[token] = Item(fs, path, size, mime)
        val name = java.net.URLEncoder.encode(path.substringAfterLast('/'), "UTF-8").replace("+", "%20")
        return "http://127.0.0.1:$port/$token/$name"
    }

    fun unregisterAll() = items.clear()

    private fun serve(s: Socket) {
        s.soTimeout = 60_000
        val input = s.getInputStream()
        val reqLine = readLine(input) ?: return
        val headers = HashMap<String, String>()
        while (true) {
            val l = readLine(input) ?: return
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) headers[l.substring(0, i).trim().lowercase()] = l.substring(i + 1).trim()
        }
        val parts = reqLine.split(' ')
        val method = parts.getOrNull(0) ?: return
        val token = parts.getOrNull(1)?.trimStart('/')?.substringBefore('/') ?: ""
        val out = s.getOutputStream().buffered(64 * 1024)
        val item = items[token]
        if (item == null) { out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return }
        var start = 0L
        var end = item.size - 1
        val range = headers["range"]
        val partial = range != null && range.startsWith("bytes=") && item.size > 0
        if (partial) {
            val spec = range!!.removePrefix("bytes=").substringBefore(',')
            val a = spec.substringBefore('-'); val b = spec.substringAfter('-', "")
            if (a.isEmpty()) { start = (item.size - (b.toLongOrNull() ?: 0)).coerceAtLeast(0) }
            else { start = a.toLongOrNull() ?: 0; if (b.isNotEmpty()) end = (b.toLongOrNull() ?: end).coerceAtMost(item.size - 1) }
            if (start >= item.size) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */${item.size}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
            }
        }
        val len = if (item.size >= 0) end - start + 1 else -1
        val sb = StringBuilder()
        sb.append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        sb.append("Content-Type: ${item.mime}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n")
        if (len >= 0) sb.append("Content-Length: $len\r\n")
        if (partial) sb.append("Content-Range: bytes $start-$end/${item.size}\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray())
        if (method == "HEAD" || len == 0L) { out.flush(); return }
        item.fs.openRead(item.path, start).use { src ->
            val buf = ByteArray(64 * 1024)
            var left = if (len >= 0) len else Long.MAX_VALUE
            while (left > 0) {
                val n = src.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n); left -= n
            }
        }
        out.flush()
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    override fun close() { items.clear(); runCatching { server.close() } }
}
