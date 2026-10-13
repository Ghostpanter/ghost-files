package com.ghostpanter.files.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.data.ViewPrefs
import com.ghostpanter.files.GhostApp
import com.ghostpanter.files.t
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Entries of [tab] after hidden-file filter, name filter and sorting. */
@Composable
fun visibleEntries(prefs: ViewPrefs, tab: TabState): List<FileEntry> =
    remember(tab.entries, tab.filter, prefs.showHidden, prefs.sortBy, prefs.ascending, prefs.foldersFirst) {
        computeVisible(prefs, tab)
    }

fun computeVisible(prefs: ViewPrefs, tab: TabState): List<FileEntry> {
    val f = tab.filter.trim()
    val base = tab.entries.filter { (prefs.showHidden || !it.isHidden) && (f.isEmpty() || it.name.contains(f, true)) }
    return sortEntries(base, prefs.sortBy, prefs.ascending, prefs.foldersFirst)
}

/**
 * MT-style dual pane: both panes are always visible side by side (also on phones in portrait),
 * separated by a draggable divider. [ratio] is the left pane's share of the width.
 */
@Composable
fun DualPane(host: PaneHost, ratio: Float, onRatio: (Float) -> Unit, onRatioDone: () -> Unit, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val total = maxWidth
        val density = LocalDensity.current
        val totalPx = with(density) { total.toPx() }.coerceAtLeast(1f)
        val r = ratio.coerceIn(0.2f, 0.8f)
        val divider = 6.dp
        val leftW = (total - divider) * r
        val rightW = total - divider - leftW
        val dragState = rememberDraggableState { delta -> onRatio((r + delta / totalPx).coerceIn(0.2f, 0.8f)) }
        Row(Modifier.fillMaxSize()) {
            PaneView(host, host.panes[0], leftW, Modifier.width(leftW))
            Box(
                Modifier.width(divider).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.outlineVariant)
                    .draggable(dragState, Orientation.Horizontal, onDragStopped = { onRatioDone() })
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onRatio(0.5f); onRatioDone() }) },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.width(2.dp).height(28.dp).clip(RoundedCornerShape(1.dp)).background(MaterialTheme.colorScheme.outline))
            }
            PaneView(host, host.panes[1], rightW, Modifier.width(rightW))
        }
    }
}

@Composable
fun PaneView(host: PaneHost, pane: PaneState, width: Dp, modifier: Modifier) {
    val active = host.activePane == pane.index
    Column(
        modifier.fillMaxHeight()
            // Any touch inside a pane makes it the active one (MT behaviour), without consuming the event.
            .pointerInput(pane.index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    host.activePane = pane.index
                }
            },
    ) {
        if (pane.tabs.size > 1) TabStrip(host, pane, active)
        val tab = pane.tab
        PathBar(host, pane, tab, active)
        Box(Modifier.weight(1f).fillMaxWidth()) { FileList(host, pane, tab, wide = width >= 360.dp) }
        StatusLine(host, tab, active)
    }
}

@Composable
private fun TabStrip(host: PaneHost, pane: PaneState, active: Boolean) {
    Row(
        Modifier.fillMaxWidth().height(26.dp).background(MaterialTheme.colorScheme.surfaceContainer).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pane.tabs.forEachIndexed { i, tb ->
            val sel = i == pane.activeTab
            Row(
                Modifier.fillMaxHeight()
                    .background(if (sel) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { host.selectTab(pane, i) }
                    .padding(start = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tb.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                    color = if (sel && active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (sel) FontWeight.Medium else FontWeight.Normal,
                )
                Icon(Icons.Filled.Close, t("关闭", "Close"), Modifier.size(18.dp).clickable { host.closeTab(pane, i) }.padding(3.dp))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PathBar(host: PaneHost, pane: PaneState, tab: TabState, active: Boolean) {
    var filtering by remember { mutableStateOf(tab.filter.isNotEmpty()) }
    val scroll = rememberScrollState()
    LaunchedEffect(tab.path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val bg = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.background(bg)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(30.dp)) {
            Text(
                host.fsLabel(tab.fsId), fontSize = 10.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).widthIn(max = 72.dp)
                    .background(fg.copy(alpha = 0.16f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Row(
                Modifier.weight(1f).horizontalScroll(scroll).padding(start = 2.dp)
                    .combinedClickable(onClick = { host.activePane = pane.index }, onLongClick = { host.onPathLongPress(pane) }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val parts = tab.path.trim('/').split('/').filter { it.isNotEmpty() }
                Text("/", Modifier.clickable { host.navigateTo(pane, "/") }.padding(horizontal = 3.dp, vertical = 6.dp), fontSize = 12.sp, color = fg)
                var acc = ""
                parts.forEachIndexed { i, part ->
                    acc += "/$part"
                    val target = acc
                    val last = i == parts.size - 1
                    Text(
                        part + if (!last) " /" else "",
                        Modifier.clickable { host.navigateTo(pane, target) }.padding(horizontal = 2.dp, vertical = 6.dp),
                        fontSize = 12.sp, maxLines = 1, color = fg,
                        fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                Spacer(Modifier.width(4.dp))
            }
            Icon(
                Icons.Filled.FilterList, t("筛选", "Filter"),
                Modifier.size(28.dp).clickable { filtering = !filtering; if (!filtering) tab.filter = "" }.padding(5.dp), tint = fg,
            )
        }
        if (filtering) {
            BasicFilterField(tab)
        }
    }
}

@Composable
private fun BasicFilterField(tab: TabState) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        androidx.compose.foundation.text.BasicTextField(
            value = tab.filter, onValueChange = { tab.filter = it }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            decorationBox = { inner ->
                if (tab.filter.isEmpty()) Text(t("筛选当前目录…", "Filter…"), fontSize = 13.sp, color = MaterialTheme.colorScheme.outline)
                inner()
            },
        )
    }
}

@Composable
private fun StatusLine(host: PaneHost, tab: TabState, active: Boolean) {
    val visible = visibleEntries(host.prefs, tab)
    val dirs = visible.count { it.isDirectory }
    val text = buildString {
        append(t("$dirs 个文件夹 · ${visible.size - dirs} 个文件", "$dirs dirs · ${visible.size - dirs} files"))
        if (tab.selecting) append(t(" · 已选 ${tab.selected.size}", " · ${tab.selected.size} sel"))
    }
    Text(
        text, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun FileList(host: PaneHost, pane: PaneState, tab: TabState, wide: Boolean) {
    val entries = visibleEntries(host.prefs, tab)
    val state = tab.listState
    LaunchedEffect(tab.highlight, entries.size) {
        val h = tab.highlight ?: return@LaunchedEffect
        val idx = entries.indexOfFirst { it.path == h }
        if (idx >= 0 && state.firstVisibleItemIndex == 0) state.scrollToItem((idx - 3).coerceAtLeast(0))
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            if (tab.path != "/") item(key = "..") {
                ParentRow { host.navigateTo(pane, com.ghostpanter.files.core.VPath.parent(tab.path)) }
            }
            items(entries.size, key = { entries[it].path }) { i ->
                val e = entries[i]
                FileRow(
                    e = e,
                    selecting = tab.selecting,
                    selected = e.path in tab.selected,
                    highlighted = e.path == tab.highlight,
                    wide = wide,
                    onClick = { host.onEntryClick(pane, e) },
                    onLongClick = { host.onEntryLongPress(pane, e) },
                    onSwipe = { host.onEntrySwipe(pane, e) },
                )
            }
        }
        when {
            tab.loading && entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 3.dp)
            tab.error != null -> Column(Modifier.align(Alignment.Center).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Text(tab.error!!, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                if (tab.fsId == LocalFs.ID && (tab.path.contains("/Android/data") || tab.path.contains("/Android/obb"))) {
                    Text(t("Android 11+ 需通过 Shizuku / Root / SAF 访问此目录（侧边栏）", "Use Shizuku / Root / SAF from the drawer"), fontSize = 11.sp)
                }
                TextButton(onClick = { host.reload(tab) }) { Text(t("重试", "Retry")) }
            }
            !tab.loading && entries.isEmpty() -> Text(t("空目录", "Empty"), Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.outline, fontSize = 12.sp)
        }
        if (tab.loading && entries.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
    }
}

@Composable
private fun ParentRow(onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.DriveFolderUpload, null, tint = Color(0xFFF2B732), modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(6.dp))
        Text("..", fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    e: FileEntry,
    selecting: Boolean,
    selected: Boolean,
    highlighted: Boolean,
    wide: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: () -> Unit,
) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        highlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    val swipePx = with(LocalDensity.current) { 40.dp.toPx() }
    Row(
        Modifier.fillMaxWidth().background(bg)
            // MT: a horizontal swipe on a row toggles its selection (enters multi-select).
            .pointerInput(e.path) {
                var acc = 0f
                var fired = false
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f; fired = false },
                    onHorizontalDrag = { change, d ->
                        acc += d
                        if (!fired && kotlin.math.abs(acc) > swipePx) { fired = true; onSwipe() }
                        change.consume()
                    },
                )
            }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = iconFor(e)
        val showThumbs = GhostApp.graph.settings.showThumbnails
        var thumb by remember(e.path, showThumbs) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(e.path, showThumbs) {
            if (!showThumbs || e.isDirectory) { thumb = null; return@LaunchedEffect }
            thumb = withContext(Dispatchers.IO) {
                val local = File(e.path).takeIf { it.isFile && it.canRead() }
                Thumbnails.load("local", e, local, size = 96)
            }
        }
        Box(Modifier.size(28.dp)) {
            val tb = thumb
            if (tb != null) Image(tb.asImageBitmap(), null, Modifier.size(28.dp).clip(RoundedCornerShape(3.dp)))
            else Icon(icon, null, tint = tint, modifier = Modifier.size(28.dp))
            if (e.isLink) Icon(Icons.Filled.Link, null, Modifier.size(12.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 15.sp,
                color = if (e.isHidden) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTimeShort(e.lastModified), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (wide) e.permissions?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 6.dp), maxLines = 1)
                }
                if (!e.isDirectory) Text(formatSize(e.size), fontSize = 10.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
            }
        }
        if (selecting) {
            Icon(
                if (selected) Icons.Filled.CheckBox else Icons.Filled.CheckBoxOutlineBlank, null,
                Modifier.padding(start = 2.dp).size(18.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
fun iconFor(e: FileEntry): Pair<ImageVector, Color> {
    if (e.isDirectory) return Icons.Filled.Folder to Color(0xFFF2B732)
    return when (e.extension) {
        in IMAGE_EXT -> Icons.Filled.Image to Color(0xFF4CAF50)
        in VIDEO_EXT -> Icons.Filled.Movie to Color(0xFFE53935)
        in AUDIO_EXT -> Icons.Filled.MusicNote to Color(0xFF8E24AA)
        "apk", "apks", "xapk" -> Icons.Filled.Android to Color(0xFF3DDC84)
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "tgz" -> Icons.Filled.FolderZip to Color(0xFF795548)
        in TEXT_EXT -> Icons.Filled.Description to Color(0xFF1E88E5)
        "pdf" -> Icons.Filled.PictureAsPdf to Color(0xFFD32F2F)
        else -> Icons.AutoMirrored.Filled.InsertDriveFile to MaterialTheme.colorScheme.outline
    }
}
