package com.ghostpanter.files.core.cache

import java.io.File

/** Pure helpers for measuring and LRU-trimming on-disk caches. */
object CacheJanitor {
    fun sizeOf(file: File): Long {
        if (!file.exists()) return 0
        if (file.isFile) return file.length().coerceAtLeast(0)
        var total = 0L
        file.walkTopDown().forEach { if (it.isFile) total += it.length().coerceAtLeast(0) }
        return total
    }

    /** Delete oldest files first until the tree is at most [maxBytes]. Returns bytes freed. */
    fun lruTrim(dir: File, maxBytes: Long): Long {
        if (!dir.exists() || maxBytes < 0) return 0
        val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.lastModified() }.toList()
        var total = files.sumOf { it.length().coerceAtLeast(0) }
        var freed = 0L
        for (f in files) {
            if (total <= maxBytes) break
            val n = f.length().coerceAtLeast(0)
            if (f.delete()) { total -= n; freed += n }
        }
        pruneEmpty(dir)
        return freed
    }

    /** Delete files older than [maxAgeMs]. Returns bytes freed. */
    fun deleteOlderThan(dir: File, maxAgeMs: Long, now: Long = System.currentTimeMillis()): Long {
        if (!dir.exists() || maxAgeMs < 0) return 0
        var freed = 0L
        dir.walkTopDown().filter { it.isFile && now - it.lastModified() > maxAgeMs }.forEach { f ->
            val n = f.length().coerceAtLeast(0)
            if (f.delete()) freed += n
        }
        pruneEmpty(dir)
        return freed
    }

    /** Recursively delete [dir] if it exists. Returns bytes that were used. */
    fun deleteTree(dir: File): Long {
        if (!dir.exists()) return 0
        val n = sizeOf(dir)
        dir.deleteRecursively()
        return n
    }

    /** Delete matching files under [dir] (non-recursive when [recursive] is false). */
    fun deleteMatching(dir: File, recursive: Boolean = false, pred: (File) -> Boolean): Long {
        if (!dir.isDirectory) return 0
        val files = if (recursive) dir.walkTopDown().filter { it.isFile } else dir.listFiles()?.asSequence()?.filter { it.isFile } ?: emptySequence()
        var freed = 0L
        for (f in files) {
            if (!pred(f)) continue
            val n = f.length().coerceAtLeast(0)
            if (f.delete()) freed += n
        }
        if (recursive) pruneEmpty(dir)
        return freed
    }

    fun pruneEmpty(dir: File) {
        if (!dir.isDirectory) return
        dir.walkBottomUp().forEach { f ->
            if (f.isDirectory && f != dir && f.list().isNullOrEmpty()) f.delete()
        }
    }
}
