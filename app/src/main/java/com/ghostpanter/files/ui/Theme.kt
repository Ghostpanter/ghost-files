package com.ghostpanter.files.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Fixed (non-dynamic) palette so the dense MT-style frame looks the same on every ROM
 * (ColorOS dynamic colours made the active pane highlight hard to see).
 */
private val Light = lightColorScheme(
    primary = Color(0xFF1F6FB2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6F8),
    onPrimaryContainer = Color(0xFF0B2A47),
    secondary = Color(0xFF4F6475),
    secondaryContainer = Color(0xFFDDE7F0),
    tertiary = Color(0xFF00897B),
    background = Color(0xFFF6F8FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE6EBF0),
    onSurfaceVariant = Color(0xFF3F4A54),
    surfaceContainer = Color(0xFFEFF3F6),
    outline = Color(0xFF7A8792),
    outlineVariant = Color(0xFFD0D7DE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DB8EE),
    onPrimary = Color(0xFF00213D),
    primaryContainer = Color(0xFF1B4A75),
    onPrimaryContainer = Color(0xFFD3E6F8),
    secondary = Color(0xFFB4C8D9),
    secondaryContainer = Color(0xFF34495A),
    tertiary = Color(0xFF5FD3C3),
    background = Color(0xFF121518),
    surface = Color(0xFF16191D),
    surfaceVariant = Color(0xFF242A30),
    onSurfaceVariant = Color(0xFFC2CAD2),
    surfaceContainer = Color(0xFF1C2126),
    outline = Color(0xFF8A96A1),
    outlineVariant = Color(0xFF3A434B),
)

/** OLED: pure black canvas; keep accents/text readable. Slight lift only on variant containers. */
private val DarkOled = darkColorScheme(
    primary = Color(0xFF7DB8EE),
    onPrimary = Color(0xFF00213D),
    primaryContainer = Color(0xFF1B4A75),
    onPrimaryContainer = Color(0xFFD3E6F8),
    secondary = Color(0xFFB4C8D9),
    secondaryContainer = Color(0xFF2A3540),
    tertiary = Color(0xFF5FD3C3),
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceVariant = Color(0xFF1A1A1A),
    onSurface = Color(0xFFE8EAED),
    onSurfaceVariant = Color(0xFFC2CAD2),
    surfaceContainer = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF000000),
    surfaceContainerHigh = Color(0xFF0A0A0A),
    surfaceContainerHighest = Color(0xFF141414),
    outline = Color(0xFF8A96A1),
    outlineVariant = Color(0xFF2A2A2A),
)

@Composable
fun GhostTheme(
    dark: Boolean = isSystemInDarkTheme(),
    oledBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = when {
        dark && oledBlack -> DarkOled
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(), content = content)
}

@Composable
fun GhostThemeFromSettings(themeMode: String, oledBlack: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    GhostTheme(dark = dark, oledBlack = oledBlack, content = content)
}
