package com.ghostpanter.files.ui.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

enum class DesignDir(val id: String, val titleZh: String, val titleEn: String) {
    A("A", "精致工具", "Pro tools"),
    B("B", "Material You 现代", "Material You"),
    C("C", "macOS Finder / 极简", "Finder / Minimal"),
}

/** Direction-specific palettes (light / dark / OLED). */
object DesignThemes {
    // A — dense pro: near-monochrome + cyan accent
    private val aAccent = Color(0xFF00B4D8)
    fun a(dark: Boolean, oled: Boolean): ColorScheme = if (!dark) lightColorScheme(
        primary = aAccent, onPrimary = Color.White,
        secondary = Color(0xFF5C6B73), tertiary = aAccent,
        background = Color(0xFFF4F5F7), surface = Color(0xFFFFFFFF),
        surfaceContainer = Color(0xFFEBEEF1), surfaceContainerHigh = Color(0xFFE2E6EA),
        surfaceContainerLowest = Color.White, outline = Color(0xFFC5CCD2),
        onBackground = Color(0xFF1A1D1F), onSurface = Color(0xFF1A1D1F),
        onSurfaceVariant = Color(0xFF5C6B73), error = Color(0xFFD32F2F),
    ) else darkColorScheme(
        primary = aAccent, onPrimary = Color.Black,
        secondary = Color(0xFF9AA7AE), tertiary = aAccent,
        background = if (oled) Color.Black else Color(0xFF121416),
        surface = if (oled) Color.Black else Color(0xFF1A1D20),
        surfaceContainer = if (oled) Color(0xFF0A0A0A) else Color(0xFF22262A),
        surfaceContainerHigh = Color(0xFF2A2F34),
        surfaceContainerLowest = if (oled) Color.Black else Color(0xFF0E1012),
        outline = Color(0xFF3A4148),
        onBackground = Color(0xFFE8EAED), onSurface = Color(0xFFE8EAED),
        onSurfaceVariant = Color(0xFF9AA7AE), error = Color(0xFFEF5350),
    )

    // B — Material You tonal: soft purple seed, large radii feel
    private val bSeed = Color(0xFF6750A4)
    private val bSeedDark = Color(0xFFD0BCFF)
    fun b(dark: Boolean, oled: Boolean): ColorScheme = if (!dark) lightColorScheme(
        primary = bSeed, onPrimary = Color.White,
        secondary = Color(0xFF625B71), tertiary = Color(0xFF7D5260),
        primaryContainer = Color(0xFFEADDFF), onPrimaryContainer = Color(0xFF21005D),
        secondaryContainer = Color(0xFFE8DEF8),
        background = Color(0xFFFEF7FF), surface = Color(0xFFFEF7FF),
        surfaceContainer = Color(0xFFF3EDF7), surfaceContainerHigh = Color(0xFFECE6F0),
        surfaceContainerLowest = Color.White, outline = Color(0xFF79747E),
        onBackground = Color(0xFF1D1B20), onSurface = Color(0xFF1D1B20),
        onSurfaceVariant = Color(0xFF49454F), error = Color(0xFFB3261E),
    ) else darkColorScheme(
        primary = bSeedDark, onPrimary = Color(0xFF381E72),
        secondary = Color(0xFFCCC2DC), tertiary = Color(0xFFEFB8C8),
        primaryContainer = Color(0xFF4F378B), onPrimaryContainer = Color(0xFFEADDFF),
        secondaryContainer = Color(0xFF4A4458),
        background = if (oled) Color.Black else Color(0xFF141218),
        surface = if (oled) Color.Black else Color(0xFF141218),
        surfaceContainer = if (oled) Color(0xFF0D0D0D) else Color(0xFF211F26),
        surfaceContainerHigh = Color(0xFF2B2930),
        surfaceContainerLowest = if (oled) Color.Black else Color(0xFF0F0D13),
        outline = Color(0xFF938F99),
        onBackground = Color(0xFFE6E0E9), onSurface = Color(0xFFE6E0E9),
        onSurfaceVariant = Color(0xFFCAC4D0), error = Color(0xFFF2B8B5),
    )

    // C — Finder / macOS: blue accent, soft grays, airy
    private val cAccent = Color(0xFF007AFF)
    fun c(dark: Boolean, oled: Boolean): ColorScheme = if (!dark) lightColorScheme(
        primary = cAccent, onPrimary = Color.White,
        secondary = Color(0xFF8E8E93), tertiary = cAccent,
        background = Color(0xFFECECEC), surface = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFF2F2F7),
        surfaceContainerLowest = Color(0xFFFFFFFF), outline = Color(0xFFD1D1D6),
        onBackground = Color(0xFF1C1C1E), onSurface = Color(0xFF1C1C1E),
        onSurfaceVariant = Color(0xFF8E8E93), error = Color(0xFFFF3B30),
    ) else darkColorScheme(
        primary = Color(0xFF0A84FF), onPrimary = Color.White,
        secondary = Color(0xFF98989D), tertiary = Color(0xFF0A84FF),
        background = if (oled) Color.Black else Color(0xFF1C1C1E),
        surface = if (oled) Color.Black else Color(0xFF2C2C2E),
        surfaceContainer = if (oled) Color(0xFF0A0A0A) else Color(0xFF3A3A3C),
        surfaceContainerHigh = Color(0xFF48484A),
        surfaceContainerLowest = if (oled) Color.Black else Color(0xFF000000),
        outline = Color(0xFF545458),
        onBackground = Color(0xFFF2F2F7), onSurface = Color(0xFFF2F2F7),
        onSurfaceVariant = Color(0xFF98989D), error = Color(0xFFFF453A),
    )

    fun of(dir: DesignDir, dark: Boolean, oled: Boolean = dark) = when (dir) {
        DesignDir.A -> a(dark, oled)
        DesignDir.B -> b(dark, oled)
        DesignDir.C -> c(dark, oled)
    }
}
