package com.ghostpanter.files.core.space

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TreemapTest {
    private fun overlap(a: TRect, b: TRect): Float {
        val w = minOf(a.x + a.w, b.x + b.w) - maxOf(a.x, b.x)
        val h = minOf(a.y + a.h, b.y + b.h) - maxOf(a.y, b.y)
        return if (w > 0 && h > 0) w * h else 0f
    }

    private fun check(sizes: List<Long>, b: TRect) {
        val r = Treemap.squarify(sizes, b)
        assertEquals(sizes.size, r.size)
        val total = sizes.sum().toDouble()
        var area = 0.0
        for ((i, rect) in r.withIndex()) {
            area += rect.area
            assertTrue("$rect outside $b", rect.x >= b.x - 0.01f && rect.y >= b.y - 0.01f && rect.x + rect.w <= b.x + b.w + 0.05f && rect.y + rect.h <= b.y + b.h + 0.05f)
            val want = sizes[i] / total * b.area
            assertEquals("area of $i", want, rect.area.toDouble(), maxOf(0.5, want * 0.01))
        }
        assertEquals(b.area.toDouble(), area, b.area * 0.001)
        for (i in r.indices) for (j in i + 1 until r.size) assertTrue("overlap $i $j", overlap(r[i], r[j]) < 0.5f)
    }

    @Test fun paperExample() {
        // Bruls et al. example: 6,6,4,3,2,2,1 in a 6×4 rectangle.
        val sizes = listOf(6L, 6, 4, 3, 2, 2, 1)
        val r = Treemap.squarify(sizes, TRect(0f, 0f, 6f, 4f))
        check(sizes, TRect(0f, 0f, 6f, 4f))
        assertEquals(3f, r[0].w, 0.001f); assertEquals(2f, r[0].h, 0.001f)
        assertEquals(3f, r[1].w, 0.001f); assertEquals(2f, r[1].y, 0.001f)
        assertTrue(r.all { Treemap.aspect(it) < 3f })
    }

    @Test fun randomTilingsAreExactAndSquarish() {
        val rnd = Random(42)
        repeat(50) {
            val n = 1 + rnd.nextInt(60)
            val sizes = List(n) { 1L + rnd.nextLong(1_000_000_000L) }.sortedDescending()
            val b = TRect(10f, 20f, 300f + rnd.nextInt(700), 200f + rnd.nextInt(700))
            check(sizes, b)
            val r = Treemap.squarify(sizes, b)
            val median = r.map { Treemap.aspect(it) }.sorted()[n / 2]
            assertTrue("median aspect $median", median < 4f)
        }
    }

    @Test fun zerosAndDegenerateBounds() {
        val r = Treemap.squarify(listOf(10L, 0L, 0L), TRect(0f, 0f, 100f, 50f))
        assertEquals(5000f, r[0].area, 0.5f)
        assertEquals(0f, r[1].area); assertEquals(0f, r[2].area)
        assertTrue(Treemap.squarify(emptyList(), TRect(0f, 0f, 1f, 1f)).isEmpty())
        assertEquals(0f, Treemap.squarify(listOf(5L), TRect(0f, 0f, 0f, 10f))[0].area)
        val single = Treemap.squarify(listOf(7L), TRect(1f, 2f, 30f, 40f))[0]
        assertEquals(TRect(1f, 2f, 30f, 40f), single)
    }

    @Test fun nestedLayoutHitTesting() {
        val b = SpaceTreeBuilder("/r")
        b.addFile("/r/big/a.mp4", 600L shl 20, 1)
        b.addFile("/r/big/b.mp4", 200L shl 20, 1)
        b.addFile("/r/c.zip", 200L shl 20, 1)
        val root = b.root
        val placed = TreemapLayout.layout(
            root.children!!.map { it to it.size }, TRect(0f, 0f, 400f, 400f), header = 20f, pad = 2f, minNest = 40f,
            nestedChildren = { n -> n.children!!.map { it to it.size } },
        )
        assertEquals(2, placed.count { it.depth == 0 })
        assertEquals(2, placed.count { it.depth == 1 })
        val big = placed.first { it.node.name == "big" }
        val a = placed.first { it.node.name == "a.mp4" }
        assertTrue(a.rect.y >= big.rect.y + 20f)
        assertEquals("a.mp4", TreemapLayout.hit(placed, a.rect.x + 1, a.rect.y + 1)!!.node.name)
        assertEquals("big", TreemapLayout.hit(placed, big.rect.x + 5, big.rect.y + 5)!!.node.name)
        val nestedArea = placed.filter { it.depth == 1 }.sumOf { it.rect.area.toDouble() }
        val inner = (big.rect.w - 4) * (big.rect.h - 22)
        assertEquals(inner.toDouble(), nestedArea, inner * 0.01)
    }
}
