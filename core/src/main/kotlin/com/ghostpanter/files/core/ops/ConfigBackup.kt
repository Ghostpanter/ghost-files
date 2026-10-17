package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.tr

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Import / export settings, bookmarks and server configs as a single .gfbackup zip.
 * Passwords stay as the app's Crypto ciphertext blobs.
 */
object ConfigBackup {
    const val FORMAT = "ghost-files-backup"
    const val VERSION = 1

    data class Snapshot(
        val settingsJson: String,
        val bookmarksJson: String,
        val serversJson: String,
        val hostedJson: String = "[]",
        val exportedAt: Long = System.currentTimeMillis(),
    )

    fun exportZip(dest: File, snap: Snapshot) {
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream().buffered()).use { zip ->
            fun put(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "manifest.json",
                """{"format":"$FORMAT","version":$VERSION,"exportedAt":${snap.exportedAt}}""",
            )
            put("settings.json", snap.settingsJson)
            put("bookmarks.json", snap.bookmarksJson)
            put("servers.json", snap.serversJson)
            put("hosted.json", snap.hostedJson)
        }
    }

    fun importZip(src: File): Snapshot {
        var settings = "{}"; var bookmarks = "[]"; var servers = "[]"; var hosted = "[]"
        var exportedAt = 0L
        ZipInputStream(src.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val body = zip.readBytes().toString(Charsets.UTF_8)
                when (e.name.substringAfterLast('/')) {
                    "manifest.json" -> {
                        require(body.contains("\"$FORMAT\"") || body.contains(FORMAT)) { tr("不是 Ghost 文件备份包", "Not a 双栏文件管理器 backup file") }
                        exportedAt = Regex("\"exportedAt\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    }
                    "settings.json" -> settings = body
                    "bookmarks.json" -> bookmarks = body
                    "servers.json" -> servers = body
                    "hosted.json" -> hosted = body
                }
                zip.closeEntry()
            }
        }
        return Snapshot(settings, bookmarks, servers, hosted, exportedAt)
    }

    fun settingsToJson(
        showHidden: Boolean, foldersFirst: Boolean, ascending: Boolean, recycleBin: Boolean,
        sortBy: String, paneRatio: Float, concurrentJobs: Int, jobsPerServer: Int,
        showThumbnails: Boolean = true, viewMode: String = "LIST", oledBlack: Boolean = false,
    ): String = buildString {
        append('{')
        fun kv(k: String, v: String) { if (length > 1) append(','); append('"').append(k).append('"').append(':').append(v) }
        kv("show_hidden", showHidden.toString())
        kv("folders_first", foldersFirst.toString())
        kv("ascending", ascending.toString())
        kv("recycle_bin", recycleBin.toString())
        kv("sort_by", "\"$sortBy\"")
        kv("pane_ratio", paneRatio.toDouble().toString())
        kv("concurrent_jobs", concurrentJobs.toString())
        kv("jobs_per_server", jobsPerServer.toString())
        kv("show_thumbnails", showThumbnails.toString())
        kv("view_mode", "\"$viewMode\"")
        kv("oled_black", oledBlack.toString())
        append('}')
    }

    fun bookmarksToJson(items: List<Triple<String, String, String>>): String =
        items.joinToString(",", "[", "]") { (label, fs, path) ->
            """{"label":${jsonStr(label)},"fs":${jsonStr(fs)},"path":${jsonStr(path)}}"""
        }

    fun parseBookmarks(json: String): List<Triple<String, String, String>> {
        val out = ArrayList<Triple<String, String, String>>()
        val obj = Regex("""\{[^{}]+\}""").findAll(json)
        for (m in obj) {
            val o = m.value
            fun field(name: String): String? =
                Regex("\"$name\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").find(o)?.groupValues?.get(1)?.let { unquote(it) }
            val label = field("label") ?: continue
            val fs = field("fs") ?: continue
            val path = field("path") ?: continue
            out.add(Triple(label, fs, path))
        }
        return out
    }

    private fun jsonStr(s: String) = buildString {
        append('"')
        for (c in s) when (c) {
            '\\' -> append("\\\\"); '"' -> append("\\\""); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
            else -> append(c)
        }
        append('"')
    }

    private fun unquote(s: String) = buildString {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    '\\' -> append('\\'); '"' -> append('"'); 'n' -> append('\n'); 'r' -> append('\r'); 't' -> append('\t')
                    else -> append(s[i + 1])
                }
                i += 2
            } else { append(c); i++ }
        }
    }
}
