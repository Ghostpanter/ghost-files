package com.ghostpanter.terminal

import android.graphics.Color

/**
 * macOS Terminal–like profiles: "Basic" (black on white) in light mode, a dark "Pro"-style profile
 * in dark mode; [pureBlack] (OLED) makes the dark background #000000.
 */
data class TermTheme(
    val bg: Int,
    val fg: Int,
    val dim: Int,
    val promptUser: Int,
    val promptDir: Int,
    val cursor: Int,
    val selection: Int,
    val barBg: Int,
    val keyBg: Int,
    val keyFg: Int,
    val tabActive: Int,
    val palette: IntArray,
) {
    companion object {
        fun of(dark: Boolean, pureBlack: Boolean): TermTheme = if (dark) TermTheme(
            bg = if (pureBlack) Color.BLACK else Color.rgb(0x1c, 0x1c, 0x1e),
            fg = Color.rgb(0xe8, 0xe8, 0xe8),
            dim = Color.rgb(0x8e, 0x8e, 0x93),
            promptUser = Color.rgb(0x5a, 0xd1, 0x5a),
            promptDir = Color.rgb(0x5b, 0x9c, 0xff),
            cursor = Color.rgb(0xc7, 0xc7, 0xc7),
            selection = Color.argb(0x66, 0x5b, 0x9c, 0xff),
            barBg = if (pureBlack) Color.rgb(0x0e, 0x0e, 0x10) else Color.rgb(0x23, 0x24, 0x27),
            keyBg = if (pureBlack) Color.rgb(0x1c, 0x1c, 0x1e) else Color.rgb(0x2e, 0x2f, 0x33),
            keyFg = Color.rgb(0xe8, 0xe8, 0xe8),
            tabActive = if (pureBlack) Color.rgb(0x26, 0x26, 0x28) else Color.rgb(0x3a, 0x3b, 0x3f),
            palette = intArrayOf(
                0xff000000.toInt(), 0xffff5f56.toInt(), 0xff5ad15a.toInt(), 0xffe6c84a.toInt(),
                0xff64a8ff.toInt(), 0xffd16ad1.toInt(), 0xff4ad1d1.toInt(), 0xffd0d0d0.toInt(),
                0xff808080.toInt(), 0xffff7b72.toInt(), 0xff7ee787.toInt(), 0xfff2cc60.toInt(),
                0xff79c0ff.toInt(), 0xffe0a0e0.toInt(), 0xff76e3ea.toInt(), 0xffffffff.toInt(),
            ),
        ) else TermTheme(
            bg = Color.WHITE,
            fg = Color.rgb(0x1a, 0x1a, 0x1a),
            dim = Color.rgb(0x8e, 0x8e, 0x93),
            promptUser = Color.rgb(0x1f, 0x7a, 0x1f),
            promptDir = Color.rgb(0x34, 0x82, 0xff),
            cursor = Color.rgb(0x3a, 0x3a, 0x3c),
            selection = Color.argb(0x4d, 0x34, 0x82, 0xff),
            barBg = Color.rgb(0xf7, 0xf8, 0xfa),
            keyBg = Color.rgb(0xec, 0xee, 0xf1),
            keyFg = Color.rgb(0x1d, 0x1d, 0x1f),
            tabActive = Color.rgb(0xe4, 0xe6, 0xea),
            palette = intArrayOf(
                0xff000000.toInt(), 0xffc23621.toInt(), 0xff25bc24.toInt(), 0xffadad27.toInt(),
                0xff492ee1.toInt(), 0xffd338d3.toInt(), 0xff33bbc8.toInt(), 0xff808080.toInt(),
                0xff555555.toInt(), 0xffe54e3a.toInt(), 0xff2fa82f.toInt(), 0xff9a8a0a.toInt(),
                0xff3d6fe0.toInt(), 0xffb03db0.toInt(), 0xff1f9aa6.toInt(), 0xff1d1d1f.toInt(),
            ),
        )
    }
}
