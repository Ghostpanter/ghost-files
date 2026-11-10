package com.ghostpanter.files.core.backup

/**
 * Pages through a content-provider-like source that may silently cap each query
 * (OEM / Google Messages SMS providers often stop at ~20, 500 or 1000 rows).
 *
 * Two pagination modes:
 *  - `_id > afterId` (default; stable if the provider honours the selection)
 *  - `OFFSET` when a page does not advance the max id (selection was ignored)
 *
 * Stops when a page is empty, shorter than [pageSize], or OFFSET mode yields only duplicates.
 */
object TelephonyPager {
    const val PAGE = 400

    data class PageRequest(
        val afterId: Long,
        val offset: Int,
        val limit: Int,
        val useOffset: Boolean,
    )

    /**
     * Yields unique rows (by id) from [fetch], which should return `(id, value)` in ascending id
     * order. A missing id (`< 0`) is still emitted but cannot be de-duplicated.
     */
    fun <T> walk(
        pageSize: Int = PAGE,
        fetch: (PageRequest) -> List<Pair<Long, T>>,
    ): Sequence<T> = walkMany(listOf(fetch), pageSize)

    /** Same as [walk] across several sources (inbox + sent + …), de-duplicating by id. */
    fun <T> walkMany(
        fetches: List<(PageRequest) -> List<Pair<Long, T>>>,
        pageSize: Int = PAGE,
    ): Sequence<T> = sequence {
        val seen = HashSet<Long>()
        for (fetch in fetches) {
            var afterId = -1L
            var offset = 0
            var useOffset = false
            while (true) {
                val rows = fetch(PageRequest(afterId, offset, pageSize, useOffset))
                if (rows.isEmpty()) break
                var newCount = 0
                var maxId = afterId
                for ((id, value) in rows) {
                    if (id > maxId) maxId = id
                    if (id >= 0 && !seen.add(id)) continue
                    newCount++
                    yield(value)
                }
                if (rows.size < pageSize) break
                if (!useOffset) {
                    if (maxId <= afterId) {
                        useOffset = true
                        offset = rows.size
                    } else {
                        afterId = maxId
                    }
                } else {
                    if (newCount == 0) break
                    offset += rows.size
                }
            }
        }
    }

    fun <T> toList(
        fetches: List<(PageRequest) -> List<Pair<Long, T>>>,
        pageSize: Int = PAGE,
    ): List<T> = walkMany(fetches, pageSize).toList()

    fun <T> count(
        fetches: List<(PageRequest) -> List<Pair<Long, T>>>,
        pageSize: Int = PAGE,
    ): Int = walkMany(fetches, pageSize).count()
}
