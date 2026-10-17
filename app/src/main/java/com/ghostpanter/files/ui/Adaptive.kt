package com.ghostpanter.files.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Material window width classes (dp thresholds 600 / 840). */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

/**
 * Resolved layout for the current window.
 *
 * [tablet]: navigation rail + per-pane path headers + toolbar in the top bar (no bottom bar).
 * [permanentPanel]: drawer content shown as a persistent side panel instead of a modal drawer.
 * [previewAllowed]: there is room for the third details/preview panel.
 */
data class WindowLayout(
    val widthClass: WidthClass,
    val tablet: Boolean,
    val permanentPanel: Boolean,
    val previewAllowed: Boolean,
    val widthDp: Int,
    val heightDp: Int,
)

const val RAIL_WIDTH_DP = 72
const val SIDE_PANEL_WIDTH_DP = 264

/**
 * Pure decision function (unit-tested). [mode] is Settings.layoutMode: "auto" | "phone" | "tablet".
 * Auto: phones keep the phone layout unchanged in both orientations: compact width (< 600dp) or a
 * short window (< 480dp high, i.e. a landscape phone). Tablets and unfolded foldables (width >= 600dp
 * and height >= 480dp) get the tablet frame. "tablet" forces it anyway.
 */
fun resolveLayout(mode: String, widthDp: Int, heightDp: Int, panelOpen: Boolean = true): WindowLayout {
    val wc = when {
        widthDp >= 840 -> WidthClass.EXPANDED
        widthDp >= 600 -> WidthClass.MEDIUM
        else -> WidthClass.COMPACT
    }
    val tablet = when (mode) {
        "phone" -> false
        "tablet" -> true
        else -> wc != WidthClass.COMPACT && heightDp >= 480
    }
    // Persistent panel needs real width and some height (not a landscape phone).
    val permanent = tablet && widthDp >= 1000 && heightDp >= 480
    val forPanes = widthDp - RAIL_WIDTH_DP - (if (permanent && panelOpen) SIDE_PANEL_WIDTH_DP else 0)
    val preview = tablet && heightDp >= 480 && forPanes - 280 >= 640
    return WindowLayout(wc, tablet, permanent, preview, widthDp, heightDp)
}

@Composable
fun rememberWindowLayout(mode: String, panelOpen: Boolean): WindowLayout {
    val cfg = LocalConfiguration.current
    return remember(mode, cfg.screenWidthDp, cfg.screenHeightDp, panelOpen) {
        resolveLayout(mode, cfg.screenWidthDp, cfg.screenHeightDp, panelOpen)
    }
}

/** List/grid sizes per density option. "normal" equals the pre-0.6.9 phone values. */
data class DensitySpec(val icon: Dp, val nameSp: Int, val metaSp: Int, val rowPad: Dp, val gridCell: Dp, val gridIcon: Dp) {
    // "normal" keeps the 0.6.8 sizes (96 / 192 px) so cached thumbnails stay valid.
    val thumbPx: Int get() = if (icon <= 28.dp) 96 else (icon.value * 3.5f).toInt()
    val gridThumbPx: Int get() = if (gridIcon <= 56.dp) 192 else (gridIcon.value * 3.5f).toInt()
}

fun densitySpec(key: String): DensitySpec = when (key) {
    "compact" -> DensitySpec(22.dp, 12, 9, 2.dp, 84.dp, 44.dp)
    "large" -> DensitySpec(44.dp, 15, 11, 7.dp, 148.dp, 96.dp)
    else -> DensitySpec(28.dp, 13, 10, 4.dp, 96.dp, 56.dp)
}

val LocalDensitySpec = compositionLocalOf { densitySpec("normal") }

/**
 * Navigation-rail destinations of the tablet frame. Phase 2 (embedded scrcpy) adds its
 * 「投屏/设备」 screen here as one more entry; nothing else in the frame needs to change.
 */
data class RailDest(val label: String, val icon: ImageVector, val screen: Screen, val matches: (Screen) -> Boolean)

fun railDestinations(): List<RailDest> = listOf(
    RailDest(t("文件", "Files"), Icons.Filled.Folder, Screen.Main) {
        it !is Screen.Transfers && it !is Screen.Servers && it !is Screen.LanScan && it !is Screen.Apps && it !is Screen.Trash && it !is Screen.Terminal
    },
    RailDest(t("传输", "Transfers"), Icons.Filled.SwapVert, Screen.Transfers) { it == Screen.Transfers },
    RailDest(t("本机服务", "Hosting"), Icons.Filled.CloudUpload, Screen.Servers) { it == Screen.Servers },
    RailDest(t("局域网", "LAN"), Icons.Filled.Radar, Screen.LanScan) { it == Screen.LanScan },
    RailDest(t("应用", "Apps"), Icons.Filled.Apps, Screen.Apps) { it == Screen.Apps },
    RailDest(t("回收站", "Trash"), Icons.Filled.DeleteSweep, Screen.Trash) { it == Screen.Trash },
    RailDest(t("终端", "Terminal"), Icons.Filled.Terminal, Screen.Terminal) { it == Screen.Terminal },
)

/**
 * Full-screen viewers hide the rail (player / image viewer look right in landscape). Editors hide it
 * too: switching destinations from the rail / side panel would drop unsaved edits without the
 * editor's "discard changes?" prompt, so they are left only via their own back button.
 */
fun Screen.immersive(): Boolean = this is Screen.Player || this is Screen.Image ||
    this is Screen.Editor || this is Screen.Hex || this is Screen.MusicTag

@Composable
fun GhostNavRail(
    current: Screen,
    activeJobs: Int,
    panelOpen: Boolean,
    onMenu: () -> Unit,
    onPick: (Screen) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.width(RAIL_WIDTH_DP.dp).fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        header = {
            IconButton(onClick = onMenu) {
                Icon(if (panelOpen) Icons.Filled.MenuOpen else Icons.Filled.Menu, t("侧栏", "Side panel"))
            }
        },
    ) {
        val scroll = rememberScrollState()
        Column(Modifier.weight(1f).verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
            railDestinations().forEach { d ->
                NavigationRailItem(
                    selected = d.matches(current),
                    onClick = { onPick(d.screen) },
                    icon = {
                        if (d.screen == Screen.Transfers && activeJobs > 0) BadgedBox(badge = { Badge { Text("$activeJobs") } }) { Icon(d.icon, d.label) }
                        else Icon(d.icon, d.label)
                    },
                    label = { Text(d.label, fontSize = 11.sp, maxLines = 1) },
                )
            }
        }
    }
}

/** Vertical splitter: drag to resize, double-tap to reset; shows a resize cursor with a mouse. */
@Composable
fun SplitHandle(onDelta: (Float) -> Unit, onDone: () -> Unit, onReset: () -> Unit, width: Dp = 6.dp) {
    val state = rememberDraggableState(onDelta)
    Box(
        Modifier.width(width).fillMaxHeight()
            .background(MaterialTheme.colorScheme.outlineVariant)
            .pointerHoverIcon(PointerIcon(android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW))
            .draggable(state, Orientation.Horizontal, onDragStopped = { onDone() })
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(2.dp).height(28.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.outline))
    }
}

/** The entry the details panel describes: the single selection, else the highlighted (cursor) entry. */
fun previewTarget(tab: TabState): FileEntry? {
    val sel = tab.selected
    val path = if (sel.size == 1) sel.first() else tab.highlight
    return path?.let { p -> tab.entries.firstOrNull { it.path == p } }
}

/** Third panel on very wide windows: thumbnail / text preview + file info of the focused pane. */
@Composable
fun DetailsPanel(host: PaneHost, modifier: Modifier = Modifier) {
    val pane = host.panes[host.activePane]
    val tab = pane.tab
    val e = previewTarget(tab)
    Surface(modifier.fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text(t("详情", "Details"), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))
            if (e == null) {
                val dirs = tab.entries.count { it.isDirectory }
                Text(tab.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                InfoLine(t("位置", "Location"), host.fsLabel(tab.fsId) + "  " + tab.path)
                InfoLine(t("内容", "Contains"), t("$dirs 个文件夹 · ${tab.entries.size - dirs} 个文件", "$dirs folders · ${tab.entries.size - dirs} files"))
                InfoLine(t("总大小", "Total size"), formatSize(tab.entries.filter { !it.isDirectory }.sumOf { it.size }))
                if (tab.selected.size > 1) InfoLine(t("已选", "Selected"), "${tab.selected.size}")
                Spacer(Modifier.height(12.dp))
                Text(t("点选文件或用方向键查看预览", "Click a file or use arrow keys to preview"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
            } else EntryDetails(host, pane, tab, e)
        }
    }
}

/** Preview + info of one entry; [key] on the entry so state never leaks between files. */
@Composable
private fun EntryDetails(host: PaneHost, pane: PaneState, tab: TabState, e: FileEntry) {
    key(tab.fsId, e.path) { Column {
            var thumb by remember(e.path, e.lastModified) { mutableStateOf<android.graphics.Bitmap?>(null) }
            var text by remember(e.path, e.lastModified) { mutableStateOf<String?>(null) }
            LaunchedEffect(tab.fsId, e.path, e.lastModified) {
                thumb = null; text = null
                if (e.isDirectory) return@LaunchedEffect
                val job = coroutineContext[kotlinx.coroutines.Job]
                thumb = withContext(Dispatchers.IO) { runCatching { host.thumbnail(tab.fsId, e, 720) { job?.isActive == false } }.getOrNull() }
                if (thumb == null) text = withContext(Dispatchers.IO) { host.textPreview(tab.fsId, e) }
            }
            val (icon, tint) = iconFor(e)
            Box(
                Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                val tb = thumb
                if (tb != null) Image(tb.asImageBitmap(), e.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                else Icon(icon, null, Modifier.size(72.dp), tint = tint)
            }
            Spacer(Modifier.height(10.dp))
            Text(e.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            InfoLine(t("类型", "Type"), if (e.isDirectory) t("文件夹", "Folder") else e.extension.uppercase().ifEmpty { t("文件", "File") })
            if (!e.isDirectory) InfoLine(t("大小", "Size"), formatSize(e.size) + if (e.size >= 1024) "  (${"%,d".format(e.size)} B)" else "")
            InfoLine(t("修改时间", "Modified"), formatTime(e.lastModified))
            e.permissions?.takeIf { it.isNotEmpty() }?.let { InfoLine(t("权限", "Permissions"), it) }
            InfoLine(t("路径", "Path"), host.fsLabel(tab.fsId) + "  " + e.path)
            Spacer(Modifier.height(8.dp))
            Row {
                FilledTonalButton(onClick = { host.openEntry(pane, e) }) { Text(t("打开", "Open")) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { host.onEntryLongPress(pane, e) }) { Text(t("操作…", "Actions…")) }
            }
            text?.let { tx ->
                Spacer(Modifier.height(10.dp))
                Text(t("文本预览", "Text preview"), fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(4.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(tx.take(4000), fontSize = 11.sp, fontFamily = FontFamily.Monospace, lineHeight = 14.sp, modifier = Modifier.padding(8.dp), maxLines = 60, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Text(label, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
        Text(value, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
}

/** Pixel width helper for split computations. */
@Composable
fun Dp.px(): Float = with(LocalDensity.current) { this@px.toPx() }
