package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.core.VirtualFs
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Grep-like content search across files (cancelable, multi-encoding). */
object ContentSearch {
    data class Hit(
        val entry: FileEntry,
        val line: Int,
        val column: Int,
        val preview: String,
        val charset: String,
    )

    data class Options(
        val query: String,
        val ignoreCase: Boolean = true,
        val regex: Boolean = false,
        val wholeWord: Boolean = false,
        val recursive: Boolean = true,
        val includeHidden: Boolean = false,
        /** Empty = auto-detect among common Chinese/Western encodings. */
        val charsetNames: List<String> = emptyList(),
        val maxFileBytes: Long = 8L * 1024 * 1024,
        val maxHits: Int = 500,
        val binarySkip: Boolean = true,
        val nameFilter: String = "",
    )

    private val DEFAULT_CS = listOf("UTF-8", "GBK", "Big5", "UTF-16LE", "ISO-8859-1")

    fun search(
        fs: VirtualFs,
        root: String,
        opts: Options,
        isCancelled: () -> Boolean = { false },
        onHit: (Hit) -> Unit = {},
        onProgress: (String) -> Unit = {},
    ): Int {
        val q = opts.query
        if (q.isEmpty()) return 0
        val nameMatch = if (opts.nameFilter.isBlank()) null else FsSearch.matcher(opts.nameFilter)
        val pattern: Regex? = if (opts.regex) {
            val flags = if (opts.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            Regex(q, flags)
        } else null
        val needle = if (opts.ignoreCase) q.lowercase() else q
        var hits = 0
        val stack = ArrayDeque<String>().apply { addLast(root) }
        while (stack.isNotEmpty() && !isCancelled() && hits < opts.maxHits) {
            val dir = stack.removeLast()
            onProgress(dir)
            val children = runCatching { fs.list(dir) }.getOrNull() ?: continue
            for (c in children) {
                if (isCancelled() || hits >= opts.maxHits) break
                if (!opts.includeHidden && c.isHidden) continue
                if (c.isDirectory) {
                    if (opts.recursive && !c.isLink) stack.addLast(c.path)
                    continue
                }
                if (nameMatch != null && !nameMatch(c.name)) continue
                if (c.size <= 0 || c.size > opts.maxFileBytes) continue
                val found = scanFile(fs, c, opts, pattern, needle, isCancelled)
                for (h in found) {
                    onHit(h)
                    hits++
                    if (hits >= opts.maxHits) break
                }
            }
        }
        return hits
    }

    private fun scanFile(
        fs: VirtualFs,
        e: FileEntry,
        opts: Options,
        pattern: Regex?,
        needle: String,
        isCancelled: () -> Boolean,
    ): List<Hit> {
        val raw = ByteArrayOutputStream()
        fs.openRead(e.path).use { input ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                if (isCancelled()) return emptyList()
                val n = input.read(buf); if (n < 0) break
                raw.write(buf, 0, n); total += n
                if (total > opts.maxFileBytes) return emptyList()
            }
        }
        val bytes = raw.toByteArray()
        if (opts.binarySkip && looksBinary(bytes)) return emptyList()
        val charsets = (opts.charsetNames.ifEmpty { DEFAULT_CS }).mapNotNull {
            runCatching { Charset.forName(it) }.getOrNull()
        }
        val textCs = decodeBest(bytes, charsets) ?: return emptyList()
        val (text, cs) = textCs
        val out = ArrayList<Hit>()
        var lineNo = 1
        var i = 0
        while (i <= text.length && out.size < 50) {
            if (isCancelled()) break
            val nl = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            val line = text.substring(i, nl).trimEnd('\r')
            val matchCol = findInLine(line, opts, pattern, needle)
            if (matchCol >= 0) {
                val preview = if (line.length > 200) line.take(200) + "…" else line
                out.add(Hit(e, lineNo, matchCol + 1, preview, cs.name()))
            }
            if (nl >= text.length) break
            i = nl + 1
            lineNo++
        }
        return out
    }

    private fun findInLine(line: String, opts: Options, pattern: Regex?, needle: String): Int {
        if (pattern != null) {
            val m = pattern.find(line) ?: return -1
            return m.range.first
        }
        val hay = if (opts.ignoreCase) line.lowercase() else line
        var idx = hay.indexOf(needle)
        while (idx >= 0) {
            if (!opts.wholeWord || isWordBoundary(hay, idx, needle.length)) return idx
            idx = hay.indexOf(needle, idx + 1)
        }
        return -1
    }

    private fun isWordBoundary(s: String, start: Int, len: Int): Boolean {
        val before = if (start == 0) true else !s[start - 1].isLetterOrDigit() && s[start - 1] != '_'
        val afterIdx = start + len
        val after = if (afterIdx >= s.length) true else !s[afterIdx].isLetterOrDigit() && s[afterIdx] != '_'
        return before && after
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val n = minOf(bytes.size, 8192)
        var nul = 0
        for (i in 0 until n) if (bytes[i].toInt() == 0) nul++
        return nul > n / 100 || nul > 8
    }

    private fun decodeBest(bytes: ByteArray, charsets: List<Charset>): Pair<String, Charset>? {
        for (cs in charsets) {
            val dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            try {
                return dec.decode(ByteBuffer.wrap(bytes)).toString() to cs
            } catch (_: Exception) {
            }
        }
        return try {
            String(bytes, StandardCharsets.UTF_8) to StandardCharsets.UTF_8
        } catch (_: Exception) {
            null
        }
    }
}
