package com.ghostpanter.files.core.space

/** Axis-aligned rectangle in layout units. */
data class TRect(val x: Float, val y: Float, val w: Float, val h: Float) {
    val area get() = w * h
    fun inset(d: Float) = TRect(x + d, y + d, (w - 2 * d).coerceAtLeast(0f), (h - 2 * d).coerceAtLeast(0f))
    fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h
}

/**
 * Squarified treemap (Bruls, Huizing, van Wijk 2000). [sizes] must be sorted descending (zero and
 * negative sizes get empty rects); the result has one rect per size, in the same order, tiling
 * [bounds] exactly.
 */
object Treemap {
    fun squarify(sizes: List<Long>, bounds: TRect): List<TRect> {
        val n = sizes.size
        val out = arrayOfNulls<TRect>(n)
        val total = sizes.sumOf { if (it > 0) it.toDouble() else 0.0 }
        if (n == 0) return emptyList()
        if (total <= 0 || bounds.w <= 0 || bounds.h <= 0) return List(n) { TRect(bounds.x, bounds.y, 0f, 0f) }
        val scale = bounds.w.toDouble() * bounds.h / total
        val areas = DoubleArray(n) { if (sizes[it] > 0) sizes[it] * scale else 0.0 }
        var x = bounds.x.toDouble(); var y = bounds.y.toDouble()
        var w = bounds.w.toDouble(); var h = bounds.h.toDouble()
        var i = 0
        // Trailing zero sizes: empty rects at the end.
        var last = n
        while (last > 0 && areas[last - 1] <= 0.0) last--
        while (i < last) {
            val side = minOf(w, h)
            var j = i
            var rowSum = 0.0
            var rowMin = Double.MAX_VALUE; var rowMax = 0.0
            var best = Double.MAX_VALUE
            while (j < last) {
                val a = areas[j]
                val s = rowSum + a
                val mn = minOf(rowMin, a); val mx = maxOf(rowMax, a)
                val worst = worst(s, mn, mx, side)
                if (j > i && worst > best) break
                best = worst; rowSum = s; rowMin = mn; rowMax = mx; j++
            }
            // Lay row [i, j) along the short side.
            val horizontalRow = w >= h // row stacks vertically in a column on the left
            if (horizontalRow) {
                val colW = if (j >= last) w else rowSum / h
                var cy = y
                for (k in i until j) {
                    val rh = if (k == j - 1) (y + h - cy) else areas[k] / colW
                    out[k] = TRect(x.toFloat(), cy.toFloat(), colW.toFloat(), rh.toFloat())
                    cy += rh
                }
                x += colW; w -= colW
            } else {
                val rowH = if (j >= last) h else rowSum / w
                var cx = x
                for (k in i until j) {
                    val rw = if (k == j - 1) (x + w - cx) else areas[k] / rowH
                    out[k] = TRect(cx.toFloat(), y.toFloat(), rw.toFloat(), rowH.toFloat())
                    cx += rw
                }
                y += rowH; h -= rowH
            }
            i = j
        }
        for (k in last until n) out[k] = TRect(x.toFloat(), y.toFloat(), 0f, 0f)
        return out.map { it!! }
    }

    /** Worst aspect ratio of a row with total [s], min/max areas, laid against [side]. */
    private fun worst(s: Double, mn: Double, mx: Double, side: Double): Double {
        val s2 = s * s; val w2 = side * side
        return maxOf(w2 * mx / s2, s2 / (w2 * mn))
    }

    fun aspect(r: TRect): Float = if (r.w <= 0 || r.h <= 0) Float.MAX_VALUE else maxOf(r.w / r.h, r.h / r.w)
}

/** One placed block: [node] at [rect], [depth] 0 = child of the shown folder, 1 = grandchild. */
class Placed(val node: SpaceNode, val rect: TRect, val depth: Int, val bytes: Long)

/**
 * Two-level layout of a folder: its children squarified into [bounds]; folders big enough get a
 * header strip of [header] and their own children laid inside. [sizeOf] gives the (filtered)
 * size; blocks smaller than [minSide] on either side are not nested.
 */
object TreemapLayout {
    fun layout(
        children: List<Pair<SpaceNode, Long>>,
        bounds: TRect,
        header: Float,
        pad: Float,
        minNest: Float,
        nestedChildren: (SpaceNode) -> List<Pair<SpaceNode, Long>>,
        maxBlocks: Int = 400,
    ): List<Placed> {
        val top = children.filter { it.second > 0 }.sortedByDescending { it.second }.take(maxBlocks)
        val rects = Treemap.squarify(top.map { it.second }, bounds)
        val out = ArrayList<Placed>(top.size * 4)
        for ((idx, pr) in top.withIndex()) {
            val (node, bytes) = pr
            val r = rects[idx]
            out.add(Placed(node, r, 0, bytes))
            if (node.drillable && r.w >= minNest && r.h >= minNest + header) {
                val inner = TRect(r.x + pad, r.y + header, r.w - 2 * pad, r.h - header - pad)
                if (inner.w <= 0 || inner.h <= 0) continue
                val kids = nestedChildren(node).filter { it.second > 0 }.take(maxBlocks / 4)
                // Nested blocks keep the parent's scale: unseen remainder leaves a gap like SpaceSniffer.
                val sum = kids.sumOf { it.second }
                if (sum <= 0) continue
                val frac = (sum.toDouble() / bytes).coerceAtMost(1.0)
                val area = TRect(inner.x, inner.y, inner.w, inner.h)
                val sub = if (frac >= 0.999) area else {
                    // Shrink along the long side so the shown share matches the bytes share.
                    if (area.w >= area.h) area.copy(w = (area.w * frac).toFloat()) else area.copy(h = (area.h * frac).toFloat())
                }
                val kr = Treemap.squarify(kids.map { it.second }, sub)
                for ((k, kp) in kids.withIndex()) if (kr[k].w >= 1f && kr[k].h >= 1f) out.add(Placed(kp.first, kr[k], 1, kp.second))
            }
        }
        return out
    }

    /** Deepest placed block under a point (grandchildren win over their folder). */
    fun hit(placed: List<Placed>, x: Float, y: Float): Placed? =
        placed.lastOrNull { it.depth == 1 && it.rect.contains(x, y) } ?: placed.lastOrNull { it.depth == 0 && it.rect.contains(x, y) }
}
