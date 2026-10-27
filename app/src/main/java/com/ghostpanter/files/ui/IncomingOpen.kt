package com.ghostpanter.files.ui

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/**
 * Resolve ACTION_VIEW / ACTION_SEND URIs from system chooser and OEM file managers
 * (ColorOS / HyperOS / MIUI / WeChat / QQ Downloads providers, etc.).
 */
object IncomingOpen {
    data class Resolved(val file: File, val displayName: String, val mime: String?)

    fun urisFrom(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        return when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                    ?: intent.data
            )
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                if (!list.isNullOrEmpty()) list.filterNotNull()
                else (0 until (intent.clipData?.itemCount ?: 0)).mapNotNull { intent.clipData!!.getItemAt(it).uri }
            }
            else -> emptyList()
        }
    }

    fun displayName(cr: ContentResolver, uri: Uri, fallback: String = "file"): String {
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/') ?: fallback
        var name: String? = null
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c: Cursor ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = c.getString(i)
                }
            }
        }
        if (!name.isNullOrBlank()) return name!!
        // OEM content paths sometimes encode the name in the last segment
        val seg = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
        return seg?.takeIf { it.isNotBlank() && '.' in it } ?: fallback
    }

    fun sizeHint(cr: ContentResolver, uri: Uri): Long {
        if (uri.scheme == "file") return File(uri.path ?: return -1L).length()
        runCatching {
            cr.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.SIZE)
                    if (i >= 0) return c.getLong(i)
                }
            }
        }
        return -1L
    }

    /**
     * Prefer a real filesystem path when the URI is file:// or a DocumentsProvider
     * that maps to /storage/...; otherwise copy into cache so in-app preview/player works.
     */
    fun materialize(ctx: Context, uri: Uri, mimeHint: String?): Resolved {
        val cr = ctx.contentResolver
        runCatching {
            cr.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        // Best-effort read grant for the lifetime of this activity
        runCatching {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val name = displayName(cr, uri)
        val mime = mimeHint?.takeIf { it.isNotBlank() && it != "*/*" }
            ?: runCatching { cr.getType(uri) }.getOrNull()
            ?: mimeOf(name)

        if (uri.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrBlank()) {
                val f = File(path)
                if (f.isFile && f.canRead()) return Resolved(f, name, mime)
            }
        }

        // Try to unwrap content://…/document/primary:Download/foo.apk → /storage/emulated/0/Download/foo.apk
        localPathFromContent(uri)?.let { path ->
            val f = File(path)
            if (f.isFile && f.canRead()) return Resolved(f, name.ifBlank { f.name }, mime)
        }

        val dir = File(ctx.cacheDir, "incoming").apply { mkdirs() }
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "file" }
        val out = File(dir, "${Integer.toHexString(uri.hashCode())}_$safe")
        val hint = sizeHint(cr, uri)
        if (!(out.exists() && hint >= 0 && out.length() == hint)) {
            cr.openInputStream(uri)?.use { input ->
                out.outputStream().use { input.copyTo(it, 256 * 1024) }
            } ?: error("cannot open $uri")
        }
        return Resolved(out, name.ifBlank { out.name }, mime)
    }

    /** Cheap unwrap for common AOSP / OEM DocumentsProvider encodings. */
    fun localPathFromContent(uri: Uri): String? {
        if (uri.scheme != "content") return null
        val path = uri.path ?: return null
        // content://com.android.externalstorage.documents/document/primary%3ADownload%2Ffoo.apk
        val doc = path.substringAfter("/document/", "").ifBlank {
            path.substringAfter("/tree/", "").substringAfter("/document/", "")
        }
        if (doc.isBlank()) return null
        val decoded = Uri.decode(doc)
        return when {
            decoded.startsWith("primary:") ->
                "/storage/emulated/0/" + decoded.removePrefix("primary:").replace('/', File.separatorChar)
            Regex("^[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4}:").containsMatchIn(decoded) -> {
                val vol = decoded.substringBefore(':')
                val rest = decoded.substringAfter(':')
                "/storage/$vol/$rest"
            }
            else -> null
        }
    }
}
