package com.ghostpanter.files.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.TopAppBarColors
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * 「HyperOS 清爽」design system (direction D).
 * Light gray canvas, white rounded cards, one blue accent, soft type-colored file icons,
 * hairline dividers. Fixed palette (no dynamic color) so every ROM renders the same.
 */

/** The single accent. */
val Accent = Color(0xFF3482FF)
private val AccentDark = Color(0xFF5B9CFF)

private val Light = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F1FF),
    onPrimaryContainer = Color(0xFF0B3A82),
    secondary = Color(0xFF5C6370),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F1FF),
    onSecondaryContainer = Color(0xFF0B3A82),
    tertiary = Color(0xFF2FA866),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE3F6EA),
    onTertiaryContainer = Color(0xFF0B3D20),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF1A1A1A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFEEF0F3),
    onSurfaceVariant = Color(0xFF5C6068),
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE9EBEE),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = Color(0xFF8A8F98),
    outlineVariant = Color(0xFFE6E8EC),
    error = Color(0xFFE5484D),
    onError = Color.White,
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF7A1518),
    inverseSurface = Color(0xFF2A2B2E),
    inverseOnSurface = Color(0xFFF2F2F7),
    inversePrimary = AccentDark,
    scrim = Color(0xFF000000),
)

private val Dark = darkColorScheme(
    primary = AccentDark,
    onPrimary = Color(0xFF00204D),
    primaryContainer = Color(0xFF1A2A44),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = Color(0xFFA6A9B0),
    onSecondary = Color(0xFF1A1B1E),
    secondaryContainer = Color(0xFF1A2A44),
    onSecondaryContainer = Color(0xFFD6E4FF),
    tertiary = Color(0xFF4CC38A),
    onTertiary = Color(0xFF00210F),
    tertiaryContainer = Color(0xFF173626),
    onTertiaryContainer = Color(0xFFCDEFD9),
    background = Color(0xFF101113),
    onBackground = Color(0xFFF2F2F7),
    surface = Color(0xFF1A1B1E),
    onSurface = Color(0xFFF2F2F7),
    surfaceVariant = Color(0xFF26272B),
    onSurfaceVariant = Color(0xFFA6A9B0),
    surfaceTint = Color.Transparent,
    surfaceBright = Color(0xFF2A2B2F),
    surfaceDim = Color(0xFF101113),
    surfaceContainerLowest = Color(0xFF0C0D0F),
    surfaceContainerLow = Color(0xFF1A1B1E),
    surfaceContainer = Color(0xFF1A1B1E),
    surfaceContainerHigh = Color(0xFF222326),
    surfaceContainerHighest = Color(0xFF1A1B1E),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFF2C2D31),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3B0A0A),
    errorContainer = Color(0xFF3A1416),
    onErrorContainer = Color(0xFFFFD9D9),
    inverseSurface = Color(0xFFF2F2F7),
    inverseOnSurface = Color(0xFF1A1B1E),
    inversePrimary = Accent,
)

/** OLED: pure black canvas; cards are a hair lighter so the dual-pane cards still read. */
private val DarkOled = Dark.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF0E0E10),
    surfaceDim = Color(0xFF000000),
    surfaceBright = Color(0xFF1C1C1E),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0E0E10),
    surfaceContainer = Color(0xFF0E0E10),
    surfaceContainerHigh = Color(0xFF161618),
    surfaceContainerHighest = Color(0xFF0E0E10),
    surfaceVariant = Color(0xFF1C1C1E),
    secondaryContainer = Color(0xFF14243C),
    outlineVariant = Color(0xFF222224),
)

/** Corner scale: list chips 8, buttons/pills 12, cards 16–20, sheets/dialogs 28. */
val GhostShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()

/** Refined type scale: bold large titles, medium body, light meta. System font (MiSans/HarmonyOS Sans on those ROMs). */
val GhostTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium),
)

/** Tokens Material's scheme has no slot for. */
@Immutable
data class GhostColors(
    val dark: Boolean,
    val folder: Color,
    val image: Color,
    val video: Color,
    val audio: Color,
    val apk: Color,
    val archive: Color,
    val text: Color,
    val pdf: Color,
    val other: Color,
    /** Unfocused pane card fill (focused uses colorScheme.surface). */
    val paneIdle: Color,
)

private val LightTokens = GhostColors(
    dark = false,
    folder = Color(0xFF3482FF), image = Color(0xFFFF7A59), video = Color(0xFF8E6CF0), audio = Color(0xFFF2557D),
    apk = Color(0xFF2FBF71), archive = Color(0xFFF5A524), text = Color(0xFF5B7FA6), pdf = Color(0xFFE5484D),
    other = Color(0xFF9AA0A8), paneIdle = Color(0xFFFCFCFD),
)
private val DarkTokens = GhostColors(
    dark = true,
    folder = Color(0xFF5B9CFF), image = Color(0xFFFF8A6B), video = Color(0xFFA48BF5), audio = Color(0xFFF56E92),
    apk = Color(0xFF4CC38A), archive = Color(0xFFF7B53F), text = Color(0xFF86A3C4), pdf = Color(0xFFFF6B6B),
    other = Color(0xFF8E8E93), paneIdle = Color(0xFF16171A),
)
private val OledTokens = DarkTokens.copy(paneIdle = Color(0xFF070708))

val LocalGhostColors = staticCompositionLocalOf { LightTokens }

object Ghost {
    val colors: GhostColors @Composable get() = LocalGhostColors.current
}

/** Sub-screen top bars sit on the canvas (no white strip), like HyperOS. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ghostTopBarColors(): TopAppBarColors = TopAppBarDefaults.topAppBarColors(
    containerColor = MaterialTheme.colorScheme.background,
    scrolledContainerColor = MaterialTheme.colorScheme.background,
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
    val tokens = when {
        dark && oledBlack -> OledTokens
        dark -> DarkTokens
        else -> LightTokens
    }
    CompositionLocalProvider(LocalGhostColors provides tokens) {
        MaterialTheme(colorScheme = scheme, typography = GhostTypography, shapes = GhostShapes, content = content)
    }
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

/**
 * Soft pill chip (direction D): tinted fill, no outline, accent-tinted icon. Drop-in for
 * Material's AssistChip(onClick, label, leadingIcon = …) call shape.
 */
@Composable
fun GhostChip(
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.Row(
        modifier.height(36.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(cs.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = if (leadingIcon != null) 12.dp else 14.dp, end = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        val fg = if (enabled) cs.onSurface else cs.outline
        if (leadingIcon != null) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (enabled) cs.primary else cs.outline) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.size(17.dp)) { leadingIcon() }
            }
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
        }
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides fg,
            androidx.compose.material3.LocalTextStyle provides TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = fg),
        ) { label() }
    }
}

/** HyperOS-like switch: filled gray track when off, white thumb, accent track when on. */
@Composable
fun GhostSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val dark = Ghost.colors.dark
    androidx.compose.material3.Switch(
        checked = checked, onCheckedChange = onCheckedChange, modifier = modifier, enabled = enabled,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = cs.primary,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = if (dark) Color(0xFF3A3B3F) else Color(0xFFDDE0E5),
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Borderless filter chip: gray pill when off, soft-blue pill with accent text when on. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GhostFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.FilterChip(
        selected = selected, onClick = onClick, label = label, modifier = modifier, enabled = enabled,
        leadingIcon = leadingIcon,
        shape = androidx.compose.foundation.shape.CircleShape,
        border = null,
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            containerColor = cs.surfaceVariant,
            labelColor = cs.onSurfaceVariant,
            iconColor = cs.onSurfaceVariant,
            selectedContainerColor = cs.primaryContainer,
            selectedLabelColor = cs.primary,
            selectedLeadingIconColor = cs.primary,
        ),
    )
}
