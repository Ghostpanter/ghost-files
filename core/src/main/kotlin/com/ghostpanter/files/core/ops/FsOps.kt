package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs

/** Recursive name search usable on any backend (local, root, LAN). */
object FsSearch {
    fun matcher(query: String): (String) -> Boolean {
        val q = query.trim()
        if (q.contains('*') || q.contains('?')) {
            val rx = Regex(
                q.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } },
                RegexOption.IGNORE_CASE,
            )
            return { rx.matches(it) }
        }
        return { it.contains(q, ignoreCase = true) }
    }

    fun search(
        fs: VirtualFs,
        root: String,
        query: String,
        recursive: Boolean = true,
        includeHidden: Boolean = true,
        maxResults: Int = 2000,
        isCancelled: () -> Boolean = { false },
        onResult: (FileEntry) -> Unit,
    ): Int {
        val match = matcher(query)
        var count = 0
        val stack = ArrayDeque<String>()
        stack.addLast(root)
        while (stack.isNotEmpty() && !isCancelled() && count < maxResults) {
            val dir = stack.removeLast()
            val children = runCatching { fs.list(dir) }.getOrNull() ?: continue
            for (c in children) {
                if (isCancelled() || count >= maxResults) break
                if (!includeHidden && c.isHidden) continue
                if (match(c.name)) { onResult(c); count++ }
                if (recursive && c.isDirectory && !c.isLink) stack.addLast(c.path)
            }
        }
        return count
    }
}

data class DirStats(val files: Long, val dirs: Long, val bytes: Long)

object FsStats {
    fun measure(fs: VirtualFs, entry: FileEntry, isCancelled: () -> Boolean = { false }, onProgress: (DirStats) -> Unit = {}): DirStats {
        if (!entry.isDirectory) return DirStats(1, 0, entry.size)
        var files = 0L; var dirs = 0L; var bytes = 0L
        val stack = ArrayDeque<String>().apply { addLast(entry.path) }
        var lastReport = 0L
        while (stack.isNotEmpty() && !isCancelled()) {
            val d = stack.removeLast()
            val children = runCatching { fs.list(d) }.getOrNull() ?: continue
            for (c in children) {
                if (c.isDirectory) { dirs++; if (!c.isLink) stack.addLast(c.path) } else { files++; bytes += c.size }
            }
            val now = System.currentTimeMillis()
            if (now - lastReport > 200) { lastReport = now; onProgress(DirStats(files, dirs, bytes)) }
        }
        return DirStats(files, dirs, bytes)
    }
}

/**
 * Optional recycle bin: deleted items are renamed into [trashDir] on the
 * same backend, with an index so they can be restored.
 */
class RecycleBin(private val fs: VirtualFs, val trashDir: String) {
    data class Item(val trashPath: String, val originalPath: String, val deletedAt: Long, val entry: FileEntry?)

    private val indexPath get() = VPath.join(trashDir, ".index")

    private fun readIndex(): MutableList<Triple<String, String, Long>> {
        if (fs.stat(indexPath) == null) return mutableListOf()
        val text = fs.openRead(indexPath).use { it.readBytes().toString(Charsets.UTF_8) }
        return text.lines().mapNotNull { l ->
            val p = l.split('\t')
            if (p.size >= 3) Triple(p[0], p[1], p[2].toLongOrNull() ?: 0) else null
        }.toMutableList()
    }

    private fun writeIndex(items: List<Triple<String, String, Long>>) {
        fs.openWrite(indexPath).use { out ->
            out.write(items.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" }.toByteArray(Charsets.UTF_8))
        }
    }

    @Synchronized
    fun moveToTrash(path: String, now: Long = System.currentTimeMillis()) {
        fs.mkdirs(trashDir)
        val name = "${now}_${VPath.name(path)}"
        val target = VPath.join(trashDir, name)
        fs.rename(path, target)
        val idx = readIndex()
        idx.add(Triple(name, VPath.normalize(path), now))
        writeIndex(idx)
    }

    @Synchronized
    fun list(): List<Item> {
        if (fs.stat(trashDir) == null) return emptyList()
        val present = fs.list(trashDir).associateBy { it.name }
        return readIndex().filter { it.first in present }.map {
            Item(VPath.join(trashDir, it.first), it.second, it.third, present[it.first])
        }.sortedByDescending { it.deletedAt }
    }

    @Synchronized
    fun restore(item: Item) {
        var target = item.originalPath
        var n = 1
        while (fs.stat(target) != null) target = VPath.join(VPath.parent(item.originalPath), VPath.numbered(VPath.name(item.originalPath), n++))
        fs.mkdirs(VPath.parent(target))
        fs.rename(item.trashPath, target)
        writeIndex(readIndex().filterNot { VPath.join(trashDir, it.first) == item.trashPath })
    }

    @Synchronized
    fun purge(item: Item) {
        fs.delete(item.trashPath, true)
        writeIndex(readIndex().filterNot { VPath.join(trashDir, it.first) == item.trashPath })
    }

    @Synchronized
    fun empty() {
        if (fs.stat(trashDir) == null) return
        for (c in fs.list(trashDir)) fs.delete(c.path, true)
    }
}
