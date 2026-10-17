package com.ghostpanter.files.fs

import com.ghostpanter.files.t

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.ghostpanter.files.core.Capability
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * Storage Access Framework tree (USB drives, SD cards, and /Android/data on
 * Android 11-12 where the system still allows granting it). Paths are
 * relative to the granted tree root.
 */
class SafFs(private val context: Context, private val tree: Uri, override val id: String) : VirtualFs {
    override val displayName: String = "SAF"
    override val capabilities = setOf(Capability.RESUME_READ)

    private val cr get() = context.contentResolver
    private val rootDocId: String = DocumentsContract.getTreeDocumentId(tree)
    private val idCache = ConcurrentHashMap<String, String>().apply { put(VPath.ROOT, rootDocId) }

    private fun docUri(docId: String) = DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private data class Row(val docId: String, val name: String, val mime: String, val size: Long, val mtime: Long)

    private fun children(docId: String): List<Row> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val out = ArrayList<Row>()
        cr.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out.add(Row(c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "", if (c.isNull(3)) 0 else c.getLong(3), if (c.isNull(4)) 0 else c.getLong(4)))
            }
        } ?: throw FsException(t("无法读取 SAF 目录（授权可能已失效）", "Cannot read SAF folder (permission may have expired)"))
        return out
    }

    private fun resolve(path: String): String? {
        val p = VPath.normalize(path)
        idCache[p]?.let { return it }
        val parentId = resolve(VPath.parent(p)) ?: return null
        val name = VPath.name(p)
        val row = children(parentId).firstOrNull { it.name == name } ?: return null
        idCache[p] = row.docId
        return row.docId
    }

    private fun toEntry(dir: String, r: Row) = FileEntry(
        VPath.join(dir, r.name), r.name, r.mime == Document.MIME_TYPE_DIR,
        if (r.mime == Document.MIME_TYPE_DIR) 0 else r.size, r.mtime,
    )

    override fun list(path: String, onChunk: ((List<FileEntry>) -> Unit)?): List<FileEntry> {
        val dir = VPath.normalize(path)
        val id = resolve(dir) ?: throw FsException(t("目录不存在: $dir", "Directory not found: $dir"))
        val rows = children(id)
        rows.forEach { idCache[VPath.join(dir, it.name)] = it.docId }
        return rows.map { toEntry(dir, it) }.also { onChunk?.invoke(it) }
    }

    override fun stat(path: String): FileEntry? {
        val p = VPath.normalize(path)
        if (p == VPath.ROOT) return FileEntry(p, "", true, 0, 0)
        idCache.remove(p)
        val parentId = resolve(VPath.parent(p)) ?: return null
        val r = children(parentId).firstOrNull { it.name == VPath.name(p) } ?: return null
        idCache[p] = r.docId
        return toEntry(VPath.parent(p), r)
    }

    override fun openRead(path: String, offset: Long): InputStream {
        val id = resolve(path) ?: throw FsException(t("不存在: $path", "Not found: $path"))
        val input = cr.openInputStream(docUri(id)) ?: throw FsException(t("无法打开: $path", "Cannot open: $path"))
        var left = offset
        while (left > 0) { val n = input.skip(left); if (n <= 0) break; left -= n }
        return input
    }

    /** Seekable descriptor for media probing (MediaMetadataRetriever / libVLC): no copy, real random access. */
    fun openFd(path: String): android.os.ParcelFileDescriptor? =
        resolve(path)?.let { id -> runCatching { cr.openFileDescriptor(docUri(id), "r") }.getOrNull() }

    override fun readAt(path: String, offset: Long, buf: ByteArray, off: Int, len: Int): Int {
        val pfd = openFd(path) ?: return super.readAt(path, offset, buf, off, len)
        return pfd.use { java.io.FileInputStream(it.fileDescriptor).channel.use { ch -> ch.read(java.nio.ByteBuffer.wrap(buf, off, len), offset) } }
    }

    override fun openWrite(path: String, offset: Long, sizeHint: Long): OutputStream {
        if (offset > 0) throw FsException(t("SAF 不支持续传写入", "SAF does not support resumed writes"))
        val p = VPath.normalize(path)
        val existing = resolve(p)
        val uri = if (existing != null) docUri(existing) else {
            val parent = resolve(VPath.parent(p)) ?: throw FsException(t("父目录不存在", "Parent directory not found"))
            val created = DocumentsContract.createDocument(cr, docUri(parent), "application/octet-stream", VPath.name(p))
                ?: throw FsException(t("无法创建: $p", "Cannot create: $p"))
            idCache[p] = DocumentsContract.getDocumentId(created)
            created
        }
        return cr.openOutputStream(uri, "wt") ?: throw FsException(t("无法写入: $p", "Cannot write: $p"))
    }

    override fun mkdirs(path: String) {
        var cur = VPath.ROOT
        for (part in VPath.normalize(path).trim('/').split('/').filter { it.isNotEmpty() }) {
            val next = VPath.join(cur, part)
            if (resolve(next) == null) {
                val parentId = resolve(cur)!!
                val created = DocumentsContract.createDocument(cr, docUri(parentId), Document.MIME_TYPE_DIR, part)
                    ?: throw FsException(t("无法创建目录: $next", "Cannot create directory: $next"))
                idCache[next] = DocumentsContract.getDocumentId(created)
            }
            cur = next
        }
    }

    override fun delete(path: String, recursive: Boolean) {
        val id = resolve(path) ?: return
        if (!DocumentsContract.deleteDocument(cr, docUri(id))) throw FsException(t("删除失败: $path", "Delete failed: $path"))
        idCache.keys.filter { VPath.isSameOrDescendant(VPath.normalize(path), it) }.forEach { idCache.remove(it) }
    }

    override fun rename(from: String, to: String) {
        val id = resolve(from) ?: throw FsException(t("不存在: $from", "Not found: $from"))
        if (VPath.parent(from) == VPath.parent(to)) {
            DocumentsContract.renameDocument(cr, docUri(id), VPath.name(to)) ?: throw FsException(t("重命名失败", "Rename failed"))
        } else {
            val src = resolve(VPath.parent(from))!!
            val dst = resolve(VPath.parent(to)) ?: throw FsException(t("目标目录不存在", "Target directory not found"))
            val moved = DocumentsContract.moveDocument(cr, docUri(id), docUri(src), docUri(dst)) ?: throw FsException(t("移动失败", "Move failed"))
            if (VPath.name(from) != VPath.name(to)) DocumentsContract.renameDocument(cr, moved, VPath.name(to))
        }
        idCache.keys.filter { VPath.isSameOrDescendant(VPath.normalize(from), it) }.forEach { idCache.remove(it) }
    }
}
