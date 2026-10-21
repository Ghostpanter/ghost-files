package com.ghostpanter.files.core.space

/** Per-app numbers from StorageStatsManager (bytes). */
data class AppStat(val pkg: String, val label: String, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
    val total get() = appBytes + dataBytes
}

/** Category totals of shared storage (StorageStatsManager.queryExternalStatsForUser). */
data class CategoryTotals(val images: Long, val videos: Long, val audio: Long, val apps: Long, val total: Long)

/**
 * Turns the scanned user-storage tree plus system-API totals into one root whose blocks add up to
 * the used space shown in system Settings:
 *
 *   used = total − free = scanned files + apps (not already scanned) + system (the rest)
 *
 * App data from the API includes Android/data|media|obb of the app, so whatever the scan already
 * counted there ([scannedAppSpecific]) is subtracted from the apps block to avoid double counting.
 */
object StorageAggregate {
    class Result(val root: SpaceNode, val used: Long, val appsBlock: Long, val systemBlock: Long, val scanned: Long)

    fun compose(
        label: String,
        total: Long,
        free: Long,
        scan: SpaceNode,
        scannedAppSpecific: Long,
        apps: List<AppStat>?,
        appsLabel: String,
        systemLabel: String,
        freeLabel: String? = null,
        detail: (AppStat) -> String = { "" },
    ): Result {
        val used = (total - free).coerceAtLeast(0)
        val root = SpaceNode("", NodeKind.AGGREGATE)
        root.name = ""
        root.detail = label
        root.addChild(scan)
        val scanned = scan.size
        var appsBytes = 0L
        if (apps != null) {
            val group = SpaceNode(appsLabel, NodeKind.AGGREGATE)
            val sumApps = apps.sumOf { it.total }
            appsBytes = (sumApps - scannedAppSpecific).coerceAtLeast(0)
            // Spread the subtraction proportionally so per-app blocks still sum to the group.
            val ratio = if (sumApps > 0) appsBytes.toDouble() / sumApps else 0.0
            var placed = 0L
            for ((i, a) in apps.sortedByDescending { it.total }.withIndex()) {
                val n = SpaceNode(a.label.ifEmpty { a.pkg }, NodeKind.AGGREGATE)
                n.flags = NodeFlags.APP; n.pkg = a.pkg; n.detail = detail(a); n.category = FileCategory.APK
                n.size = if (i == apps.size - 1) appsBytes - placed else (a.total * ratio).toLong()
                n.size = n.size.coerceAtLeast(0)
                placed += n.size
                group.addChild(n)
            }
            group.size = appsBytes; group.count = apps.size; group.category = FileCategory.APK
            root.addChild(group)
        }
        val system = (used - scanned - appsBytes).coerceAtLeast(0)
        val sys = SpaceNode(systemLabel, NodeKind.AGGREGATE).apply { size = system; flags = NodeFlags.SYSTEM }
        root.addChild(sys)
        root.size = scanned + appsBytes + system
        if (freeLabel != null && free > 0) root.addChild(SpaceNode(freeLabel, NodeKind.AGGREGATE).apply { size = free; flags = NodeFlags.FREE })
        return Result(root, used, appsBytes, system, scanned)
    }

    /** Bytes of the scan under Android/data, Android/media and Android/obb (counted again by app stats). */
    fun appSpecificBytes(scan: SpaceNode): Long {
        val android = scan.child("Android") ?: return 0
        return listOf("data", "media", "obb").sumOf { android.child(it)?.size ?: 0L }
    }
}

/**
 * Duplicate files: group by size first (only sizes seen twice are read), then by a quick hash of
 * the head and tail, then by a full SHA-256. [open] reads a file of the scanned backend.
 */
class DuplicateFinder(private val open: (String) -> java.io.InputStream, private val ctl: ScanControl = ScanControl()) {
    data class Group(val size: Long, val paths: List<String>) { val wasted get() = size * (paths.size - 1) }

    var hashedBytes = 0L; private set
    var progress: (done: Int, total: Int) -> Unit = { _, _ -> }

    fun find(files: List<Pair<String, Long>>, minBytes: Long = 1L shl 20): List<Group> {
        val bySize = files.filter { it.second >= minBytes }.groupBy({ it.second }, { it.first }).filterValues { it.size > 1 }
        val total = bySize.values.sumOf { it.size }
        var done = 0
        val out = ArrayList<Group>()
        for ((size, paths) in bySize) {
            ctl.check()
            val quick = paths.groupBy { p -> runCatching { quickHash(p, size) }.getOrNull().also { done++; progress(done, total) } }
                .filterKeys { it != null }.values.filter { it.size > 1 }
            for (cand in quick) {
                val full = if (size <= QUICK * 2) cand.groupBy { "q" } else cand.groupBy { p -> runCatching { fullHash(p) }.getOrNull() }.filterKeys { it != null }
                full.values.filter { it.size > 1 }.forEach { out.add(Group(size, it.sorted())) }
            }
        }
        return out.sortedByDescending { it.wasted }
    }

    private fun quickHash(path: String, size: Long): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        open(path).use { input ->
            val buf = ByteArray(QUICK)
            var n = readFully(input, buf, QUICK)
            md.update(buf, 0, n); hashedBytes += n
            if (size > QUICK * 2) {
                skipFully(input, size - QUICK * 2 - 0)
                n = readFully(input, buf, QUICK)
                md.update(buf, 0, n); hashedBytes += n
            }
        }
        return hex(md.digest())
    }

    private fun fullHash(path: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        open(path).use { input ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                ctl.check()
                val r = input.read(buf); if (r < 0) break
                md.update(buf, 0, r); hashedBytes += r
            }
        }
        return hex(md.digest())
    }

    private fun readFully(input: java.io.InputStream, buf: ByteArray, len: Int): Int {
        var n = 0
        while (n < len) { val r = input.read(buf, n, len - n); if (r < 0) break; n += r }
        return n
    }

    private fun skipFully(input: java.io.InputStream, count: Long) {
        var left = count
        val scratch = ByteArray(64 * 1024)
        while (left > 0) {
            ctl.check()
            val s = input.skip(left)
            if (s > 0) { left -= s; continue }
            val r = input.read(scratch, 0, minOf(scratch.size.toLong(), left).toInt())
            if (r < 0) break
            left -= r
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    companion object { const val QUICK = 64 * 1024 }
}
