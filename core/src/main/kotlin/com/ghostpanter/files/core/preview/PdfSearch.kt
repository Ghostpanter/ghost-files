package com.ghostpanter.files.core.preview

/**
 * One hit from a full-document PDF text search (page is 0-based).
 * [charIndex] / [charLength] refer to Pdfium text-page character indices when available.
 */
data class PdfSearchHit(
    val page: Int,
    val charIndex: Int,
    val charLength: Int,
    val preview: String,
)

object PdfSearch {
    /**
     * Case-insensitive scan of already-extracted page texts.
     * Used by unit tests and as a pure fallback when the native find API is unavailable.
     */
    fun findInPages(pages: List<String>, query: String, maxHits: Int = 200): List<PdfSearchHit> {
        if (query.isBlank() || maxHits <= 0) return emptyList()
        val q = query
        val qLower = q.lowercase()
        val out = ArrayList<PdfSearchHit>()
        for (page in pages.indices) {
            val text = pages[page]
            val lower = text.lowercase()
            var from = 0
            while (from < lower.length && out.size < maxHits) {
                val at = lower.indexOf(qLower, from)
                if (at < 0) break
                val end = (at + q.length).coerceAtMost(text.length)
                val previewStart = (at - 24).coerceAtLeast(0)
                val previewEnd = (end + 24).coerceAtMost(text.length)
                val preview = buildString {
                    if (previewStart > 0) append('…')
                    append(text.substring(previewStart, previewEnd).replace('\n', ' ').replace('\r', ' '))
                    if (previewEnd < text.length) append('…')
                }
                out += PdfSearchHit(page, at, end - at, preview)
                from = at + q.length.coerceAtLeast(1)
            }
            if (out.size >= maxHits) break
        }
        return out
    }
}
