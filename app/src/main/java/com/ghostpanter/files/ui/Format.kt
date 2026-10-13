package com.ghostpanter.files.ui

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.data.SortBy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatSize(b: Long): String {
    if (b < 1024) return "$b B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = b / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
    return String.format(Locale.US, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
}

private val dateFmt = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
fun formatTime(ms: Long): String = if (ms <= 0) "" else dateFmt.get()!!.format(Date(ms))
private val shortFmt = ThreadLocal.withInitial { SimpleDateFormat("yy-MM-dd HH:mm", Locale.getDefault()) }
fun formatTimeShort(ms: Long): String = if (ms <= 0) "" else shortFmt.get()!!.format(Date(ms))

val TEXT_EXT = setOf(
    "txt", "log", "json", "xml", "yml", "yaml", "ini", "conf", "cfg", "prop", "properties", "sh", "py",
    "js", "ts", "kt", "kts", "java", "c", "h", "cpp", "hpp", "html", "htm", "css", "csv", "gradle", "toml",
    "sql", "bat", "lua", "rc", "smali", "list", "srt", "ass", "nfo", "env", "gitignore",
)
val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "webm", "3gp", "flv", "ts", "m4v", "wmv")
val AUDIO_EXT = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "opus", "wma", "ape")
val FONT_EXT = setOf("ttf", "otf", "ttc", "otc", "woff")
val PREVIEW_MD = setOf("md", "markdown")

fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (ext == "apk") return "application/vnd.android.package-archive"
    return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
        in TEXT_EXT -> "text/plain"
        else -> "*/*"
    }
}

fun sortEntries(list: List<FileEntry>, by: SortBy, asc: Boolean, foldersFirst: Boolean): List<FileEntry> {
    val base: Comparator<FileEntry> = when (by) {
        SortBy.NAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortBy.SIZE -> compareBy<FileEntry> { it.size }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortBy.TIME -> compareBy<FileEntry> { it.lastModified }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SortBy.TYPE -> compareBy<FileEntry> { it.extension }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
    }
    val dir = if (asc) base else base.reversed()
    val cmp = if (foldersFirst) compareBy<FileEntry> { !it.isDirectory }.then(dir) else dir
    return list.sortedWith(cmp)
}
