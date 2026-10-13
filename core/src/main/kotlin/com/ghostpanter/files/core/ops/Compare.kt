package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import java.security.MessageDigest

/** File / folder compare: size+hash, recursive tree, and line diff for text. */
object Compare {
    enum class Kind { SAME, LEFT_ONLY, RIGHT_ONLY, DIFFERENT, TYPE_MISMATCH }

    data class Row(
        val rel: String,
        val kind: Kind,
        val left: FileEntry? = null,
        val right: FileEntry? = null,
        val leftHash: String? = null,
        val rightHash: String? = null,
        val detail: String? = null,
    )

    data class TextDiff(
        val leftLines: List<String>,
        val rightLines: List<String>,
        /** Unified-diff style lines prefixed with ' ', '+', '-'. */
        val unified: List<String>,
    )

    fun hashStream(input: java.io.InputStream, algo: String = "SHA-256", isCancelled: () -> Boolean = { false }): String {
        val md = MessageDigest.getInstance(algo)
        val buf = ByteArray(256 * 1024)
        while (true) {
            if (isCancelled()) throw InterruptedException("cancelled")
            val n = input.read(buf); if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun compareFiles(
        leftFs: VirtualFs, leftPath: String,
        rightFs: VirtualFs, rightPath: String,
        isCancelled: () -> Boolean = { false },
    ): Row {
        val l = leftFs.stat(leftPath)
        val r = rightFs.stat(rightPath)
        if (l == null && r == null) return Row(VPath.name(leftPath), Kind.SAME)
        if (l == null) return Row(VPath.name(rightPath), Kind.RIGHT_ONLY, right = r)
        if (r == null) return Row(VPath.name(leftPath), Kind.LEFT_ONLY, left = l)
        if (l.isDirectory != r.isDirectory) return Row(l.name, Kind.TYPE_MISMATCH, l, r, detail = "类型不同")
        if (l.isDirectory) return Row(l.name, Kind.SAME, l, r, detail = "目录（未展开）")
        if (l.size != r.size) {
            return Row(l.name, Kind.DIFFERENT, l, r, detail = "大小不同: ${l.size} vs ${r.size}")
        }
        val lh = leftFs.openRead(leftPath).use { hashStream(it, isCancelled = isCancelled) }
        val rh = rightFs.openRead(rightPath).use { hashStream(it, isCancelled = isCancelled) }
        return if (lh == rh) Row(l.name, Kind.SAME, l, r, lh, rh)
        else Row(l.name, Kind.DIFFERENT, l, r, lh, rh, detail = "内容哈希不同")
    }

    fun compareTrees(
        leftFs: VirtualFs, leftRoot: String,
        rightFs: VirtualFs, rightRoot: String,
        hashFiles: Boolean = true,
        isCancelled: () -> Boolean = { false },
        onProgress: (String) -> Unit = {},
    ): List<Row> {
        val leftMap = LinkedHashMap<String, FileEntry>()
        val rightMap = LinkedHashMap<String, FileEntry>()
        walk(leftFs, leftRoot, "", leftMap, isCancelled, onProgress)
        walk(rightFs, rightRoot, "", rightMap, isCancelled, onProgress)
        val keys = (leftMap.keys + rightMap.keys).toSortedSet()
        val out = ArrayList<Row>()
        for (rel in keys) {
            if (isCancelled()) break
            val l = leftMap[rel]; val r = rightMap[rel]
            when {
                l == null -> out.add(Row(rel, Kind.RIGHT_ONLY, right = r))
                r == null -> out.add(Row(rel, Kind.LEFT_ONLY, left = l))
                l.isDirectory && r.isDirectory -> out.add(Row(rel, Kind.SAME, l, r))
                l.isDirectory != r.isDirectory -> out.add(Row(rel, Kind.TYPE_MISMATCH, l, r))
                !hashFiles -> {
                    out.add(
                        if (l.size == r.size && l.lastModified == r.lastModified) Row(rel, Kind.SAME, l, r)
                        else if (l.size != r.size) Row(rel, Kind.DIFFERENT, l, r, detail = "大小 ${l.size} vs ${r.size}")
                        else Row(rel, Kind.DIFFERENT, l, r, detail = "时间不同"),
                    )
                }
                else -> {
                    onProgress(rel)
                    if (l.size != r.size) {
                        out.add(Row(rel, Kind.DIFFERENT, l, r, detail = "大小 ${l.size} vs ${r.size}"))
                    } else {
                        val lh = leftFs.openRead(l.path).use { hashStream(it, isCancelled = isCancelled) }
                        val rh = rightFs.openRead(r.path).use { hashStream(it, isCancelled = isCancelled) }
                        out.add(
                            if (lh == rh) Row(rel, Kind.SAME, l, r, lh, rh)
                            else Row(rel, Kind.DIFFERENT, l, r, lh, rh, detail = "哈希不同"),
                        )
                    }
                }
            }
        }
        return out
    }

    private fun walk(
        fs: VirtualFs, abs: String, rel: String, into: MutableMap<String, FileEntry>,
        isCancelled: () -> Boolean, onProgress: (String) -> Unit,
    ) {
        if (isCancelled()) return
        onProgress(abs)
        val children = runCatching { fs.list(abs) }.getOrNull() ?: return
        for (c in children) {
            if (isCancelled()) return
            val childRel = if (rel.isEmpty()) c.name else "$rel/${c.name}"
            into[childRel] = c
            if (c.isDirectory && !c.isLink) walk(fs, c.path, childRel, into, isCancelled, onProgress)
        }
    }

    fun textDiff(left: String, right: String, context: Int = 3): TextDiff {
        val a = left.replace("\r\n", "\n").replace('\r', '\n').lines()
        val b = right.replace("\r\n", "\n").replace('\r', '\n').lines()
        val lcs = lcsTable(a, b)
        val edits = ArrayList<Triple<Char, String?, String?>>()
        var i = a.size; var j = b.size
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] -> { edits.add(Triple(' ', a[i - 1], b[j - 1])); i--; j-- }
                j > 0 && (i == 0 || lcs[i][j - 1] >= lcs[i - 1][j]) -> { edits.add(Triple('+', null, b[j - 1])); j-- }
                else -> { edits.add(Triple('-', a[i - 1], null)); i-- }
            }
        }
        edits.reverse()
        // Keep context around changes
        val keep = BooleanArray(edits.size)
        for (k in edits.indices) if (edits[k].first != ' ') {
            for (t in (k - context).coerceAtLeast(0)..(k + context).coerceAtMost(edits.lastIndex)) keep[t] = true
        }
        val unified = ArrayList<String>()
        var skipping = false
        for (k in edits.indices) {
            if (!keep[k]) { if (!skipping) { unified.add("@@ … @@"); skipping = true }; continue }
            skipping = false
            val (op, la, lb) = edits[k]
            when (op) {
                ' ' -> unified.add(" ${la}")
                '-' -> unified.add("-$la")
                '+' -> unified.add("+$lb")
            }
        }
        return TextDiff(a, b, unified)
    }

    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        val t = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in 1..a.size) for (j in 1..b.size) {
            t[i][j] = if (a[i - 1] == b[j - 1]) t[i - 1][j - 1] + 1 else maxOf(t[i - 1][j], t[i][j - 1])
        }
        return t
    }
}
