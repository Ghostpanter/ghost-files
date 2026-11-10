package com.ghostpanter.files.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TelephonyPagerTest {

    private fun store(ids: LongRange): MutableList<Long> = ids.toMutableList()

    /** Honours `_id > afterId` and LIMIT. */
    private fun capAfter(all: List<Long>, cap: Int): (TelephonyPager.PageRequest) -> List<Pair<Long, Long>> = { req ->
        val src = if (req.useOffset) all.drop(req.offset) else all.filter { it > req.afterId }
        src.take(cap.coerceAtMost(req.limit)).map { it to it }
    }

    /** Ignores `_id > ?`, honours OFFSET + LIMIT (typical OEM cap ~20). */
    private fun capOffsetOnly(all: List<Long>, cap: Int): (TelephonyPager.PageRequest) -> List<Pair<Long, Long>> = { req ->
        val src = if (req.useOffset) all.drop(req.offset) else all
        src.take(cap.coerceAtMost(req.limit)).map { it to it }
    }

    /** Ignores both afterId and OFFSET — only the first page is ever visible. */
    private fun capStuck(all: List<Long>, cap: Int): (TelephonyPager.PageRequest) -> List<Pair<Long, Long>> = { req ->
        all.take(cap.coerceAtMost(req.limit)).map { it to it }
    }

    @Test fun uncappedSourceReturnsAll() {
        val all = (1L..2500L).toList()
        assertEquals(2500, TelephonyPager.count(listOf(capAfter(all, 10_000)), pageSize = 400))
        assertEquals(2500, TelephonyPager.toList(listOf(capAfter(all, 10_000)), pageSize = 400).size)
    }

    @Test fun pagesWhenProviderCapsAt20AndHonoursAfterId() {
        val all = (1L..137L).toList()
        val got = TelephonyPager.toList(listOf(capAfter(all, 20)), pageSize = 20)
        assertEquals(all, got)
    }

    @Test fun pagesWhenProviderCapsAt20AndOnlyHonoursOffset() {
        // The reported "19 messages" symptom: a default page of ~20, selection ignored.
        val all = (1L..400L).toList()
        val got = TelephonyPager.toList(listOf(capOffsetOnly(all, 20)), pageSize = 20)
        assertEquals(all, got)
    }

    @Test fun stopsWhenProviderIgnoresEveryPaginationKnob() {
        val all = (1L..500L).toList()
        val got = TelephonyPager.toList(listOf(capStuck(all, 19)), pageSize = 19)
        assertEquals((1L..19L).toList(), got)
    }

    @Test fun mergesBoxesAndDedupesById() {
        val inbox = (1L..30L).toList()
        val sent = (20L..50L).toList() // overlap 20..30
        val catchAll = (1L..50L).toList()
        val got = TelephonyPager.toList(
            listOf(capAfter(inbox, 10), capAfter(sent, 10), capAfter(catchAll, 10)),
            pageSize = 10,
        )
        assertEquals((1L..50L).toList(), got.sorted())
        assertEquals(50, TelephonyPager.count(
            listOf(capAfter(inbox, 10), capAfter(sent, 10), capAfter(catchAll, 10)),
            pageSize = 10,
        ))
    }

    @Test fun threadScopedQueriesFillGapsLeftByACappedGlobalUri() {
        // Global URI capped at 19; per-thread queries each honour afterId.
        val all = (1L..200L).toList()
        val threads = all.chunked(10) // 20 threads × 10 messages
        val fetches = ArrayList<(TelephonyPager.PageRequest) -> List<Pair<Long, Long>>>()
        fetches += capStuck(all, 19)
        for (t in threads) fetches += capAfter(t, 20)
        val got = TelephonyPager.toList(fetches, pageSize = 20)
        assertEquals(200, got.toSet().size)
        assertTrue(got.containsAll(all))
    }
}
