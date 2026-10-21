package com.ghostpanter.files.core.space

import com.ghostpanter.files.core.VPath

/**
 * Which copy of a duplicate group to keep. Ranked by, in order:
 * 1. location: regular media/document folders (DCIM, Pictures, Documents, Music, Movies…) beat neutral
 *    folders, which beat temporary places (Download, Quick Share, MT2, cache/tmp/.thumbnails, Android/data…);
 * 2. oldest modification time (unknown = last);
 * 3. shortest path;
 * 4. a name without a copy suffix ("(1)", "_copy", " - 副本"…).
 */
object DupRanker {
    const val PREFERRED = 0
    const val NEUTRAL = 1
    const val TEMP = 2

    private val PREFERRED_TOP = setOf("dcim", "pictures", "documents", "music", "movies", "recordings", "audiobooks", "podcasts", "ringtones", "alarms", "notifications", "books")
    private val TEMP_SEGMENTS = setOf(
        "download", "downloads", "quick share", "quickshare", "nearby share", "mt2", "cache", ".cache", "caches", "tmp", ".tmp", "temp", ".temp",
        ".thumbnails", "thumbnails", ".thumb", ".trash", ".ghosttrash", ".recycle", "recycle", "lost+found", "bluetooth", "received", "wechat_temp",
    )
    private val STORAGE_ROOT = Regex("^(/storage/emulated/\\d+|/data/media/\\d+|/sdcard|/storage/[^/]+)(/|$)")
    private val COPY = Regex("""(\s*\(\d+\)|\s*（\d+）|[ _-]+(copy|副本|拷贝)(\s*\(?\d+\)?)?|\s+-\s+(副本|copy)(\s*\d+)?)$""", RegexOption.IGNORE_CASE)

    /** Path below the storage root ("DCIM/Camera/x.jpg"), or the path itself elsewhere. */
    fun relative(path: String): String = STORAGE_ROOT.find(path)?.let { path.substring(it.range.last + 1) } ?: path.trimStart('/')

    fun place(path: String): Int {
        val p = path.lowercase()
        if (p.contains("/android/data/") || p.contains("/android/obb/") || p.contains("/android/media/")) return TEMP
        val segs = relative(path).lowercase().split('/').dropLast(1)
        if (segs.any { it in TEMP_SEGMENTS || it.endsWith("cache") || it.startsWith(".thumb") }) return TEMP
        if (segs.firstOrNull() in PREFERRED_TOP) return PREFERRED
        return NEUTRAL
    }

    fun hasCopySuffix(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        return COPY.containsMatchIn(base)
    }

    fun comparator(mtime: (String) -> Long): Comparator<String> = compareBy<String> { place(it) }
        .thenBy { mtime(it).takeIf { m -> m > 0 } ?: Long.MAX_VALUE }
        .thenBy { it.length }
        .thenBy { if (hasCopySuffix(VPath.name(it))) 1 else 0 }
        .thenBy { it }

    fun best(paths: List<String>, mtime: (String) -> Long = { 0 }): String = paths.minWith(comparator(mtime))
}

/** Pure edits of duplicate groups after files change (delete / trash / move / rename). */
object DupGroups {
    private fun gone(p: String, removed: Collection<String>) = removed.any { VPath.isSameOrDescendant(it, p) }

    /** Drops every path equal to or under one of [removed]; groups left with fewer than two copies vanish. */
    fun remove(groups: List<DuplicateFinder.Group>, removed: Collection<String>): List<DuplicateFinder.Group> {
        if (removed.isEmpty()) return groups
        return groups.mapNotNull { g ->
            val left = g.paths.filterNot { gone(it, removed) }
            if (left.size < 2) null else if (left.size == g.paths.size) g else g.copy(paths = left)
        }
    }

    /** Keeps only paths [exists] confirms (cache reopen / after a rescan). */
    fun retain(groups: List<DuplicateFinder.Group>, exists: (String) -> Boolean): List<DuplicateFinder.Group> =
        groups.mapNotNull { g -> val left = g.paths.filter(exists); if (left.size < 2) null else if (left.size == g.paths.size) g else g.copy(paths = left) }

    /** A renamed / moved path (or folder) keeps its group with the new spelling. */
    fun rename(groups: List<DuplicateFinder.Group>, from: String, to: String): List<DuplicateFinder.Group> =
        groups.map { g ->
            if (g.paths.none { VPath.isSameOrDescendant(from, it) }) g
            else g.copy(paths = g.paths.map { if (VPath.isSameOrDescendant(from, it)) to + it.removePrefix(from) else it }.sorted())
        }

    /** Keep choice of a group: the user's pick if it is in the group, else the ranked best. */
    fun keepOf(g: DuplicateFinder.Group, chosen: Set<String>, mtime: (String) -> Long): String =
        g.paths.firstOrNull { it in chosen } ?: DupRanker.best(g.paths, mtime)

    class Plan(val delete: List<String>, val bytes: Long, val groups: Int)

    /** Files to delete for [groups] (everything except each group's keep). */
    fun plan(groups: List<DuplicateFinder.Group>, chosen: Set<String>, mtime: (String) -> Long): Plan {
        val del = ArrayList<String>(); var bytes = 0L
        for (g in groups) {
            val keep = keepOf(g, chosen, mtime)
            g.paths.filter { it != keep }.forEach { del.add(it); bytes += g.size }
        }
        return Plan(del, bytes, groups.size)
    }

    fun encode(groups: List<DuplicateFinder.Group>): String =
        groups.joinToString("\n") { g -> g.size.toString() + "\t" + g.paths.joinToString("\t") }

    fun decode(s: String?): List<DuplicateFinder.Group>? {
        if (s == null) return null
        if (s.isEmpty()) return emptyList()
        return s.split('\n').mapNotNull { line ->
            val f = line.split('\t')
            val size = f.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            f.drop(1).takeIf { it.size >= 2 }?.let { DuplicateFinder.Group(size, it) }
        }
    }
}
