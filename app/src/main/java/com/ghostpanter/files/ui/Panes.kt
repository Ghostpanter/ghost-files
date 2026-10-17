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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.data.ViewPrefs
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
 * separated by a draggable divider (double-tap resets to 50/50). [ratio] is the left pane's share.
 * [perPaneHeader] (tablet frame): every pane shows its own path header.
 */
@Composable
fun DualPane(
    host: PaneHost, ratio: Float, onRatio: (Float) -> Unit, onRatioDone: () -> Unit, modifier: Modifier = Modifier,
    perPaneHeader: Boolean = false, dragAndDrop: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val total = maxWidth
        val density = LocalDensity.current
        val totalPx = with(density) { total.toPx() }.coerceAtLeast(1f)
        val r = ratio.coerceIn(0.2f, 0.8f)
        val divider = if (perPaneHeader) 8.dp else 6.dp
        val leftW = (total - divider) * r
        val rightW = total - divider - leftW
        Row(Modifier.fillMaxSize()) {
            PaneView(host, host.panes[0], leftW, Modifier.width(leftW), perPaneHeader, dragAndDrop)
            SplitHandle(
                onDelta = { delta -> onRatio((r + delta / totalPx).coerceIn(0.2f, 0.8f)) },
                onDone = onRatioDone,
                onReset = { onRatio(0.5f); onRatioDone() },
                width = divider,
            )
            PaneView(host, host.panes[1], rightW, Modifier.width(rightW), perPaneHeader, dragAndDrop)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PaneView(host: PaneHost, pane: PaneState, width: Dp, modifier: Modifier, perPaneHeader: Boolean = false, dragAndDrop: Boolean = false) {
    val active = host.activePane == pane.index
    val ctx = LocalContext.current
    var dropHover by remember { mutableStateOf(false) }
    val dropTarget = remember(pane, host) {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) { dropHover = true }
            override fun onExited(event: DragAndDropEvent) { dropHover = false }
            override fun onEnded(event: DragAndDropEvent) { dropHover = false }
            override fun onDrop(event: DragAndDropEvent): Boolean {
                dropHover = false
                val de = event.toAndroidDragEvent()
                (de.localState as? DragPayload)?.let { p ->
                    if (p.fromPane == pane.index) return false
                    host.onDropEntries(p.fromPane, p.entries, pane); return true
                }
                val clip = de.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.filter { it.scheme == "content" || it.scheme == "file" }
                if (uris.isEmpty()) return false
                (ctx as? android.app.Activity)?.let { a -> heldDropPermissions = a.requestDragAndDropPermissions(de) }
                host.onDropUris(pane, uris)
                return true
            }
        }
    }
    Column(
        modifier.fillMaxHeight()
            // Any touch inside a pane makes it the active one (MT behaviour), without consuming the event.
            .pointerInput(pane.index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    host.activePane = pane.index
                }
            }
            .then(if (dragAndDrop) Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget) else Modifier)
            .then(if (dropHover) Modifier.border(2.dp, MaterialTheme.colorScheme.primary) else Modifier),
    ) {
        if (pane.tabs.size > 1) TabStrip(host, pane, active)
        val tab = pane.tab
        // Focus indicator: the path row moved to the top bar, so mark the focused pane with a thin accent line.
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent))
        if (perPaneHeader) {
            Surface(color = if (active) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainerLow) {
                PathCrumbs(host, pane, showSide = false, modifier = Modifier.fillMaxWidth().height(34.dp).padding(start = 6.dp))
            }
        }
        if ((active && PaneUi.filterOpen) || tab.filter.isNotEmpty()) BasicFilterField(tab, active)
        Box(Modifier.weight(1f).fillMaxWidth()) { FileList(host, pane, tab, wide = width >= 360.dp, dragAndDrop = dragAndDrop) }
        StatusLine(host, tab, active)
    }
}

/** Keeps the latest cross-app drop grant alive while the import copies the files. */
private var heldDropPermissions: android.view.DragAndDropPermissions? = null

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

/** UI state shared between the top bar and the panes. */
object PaneUi {
    var filterOpen by mutableStateOf(false)
    /** A text field inside the frame has focus: keyboard shortcuts must not steal its keys. */
    var textFocused by mutableStateOf(false)
    /** Bumped by Ctrl+F: the focused pane's filter field requests focus. */
    var filterFocusRequest by mutableIntStateOf(0)
    /** Bumped when the frame should take keyboard focus back (e.g. after Esc in the filter). */
    var rootFocusRequest by mutableIntStateOf(0)
}

/**
 * Path of the focused pane, shown in the top bar (replaces the per-pane path rows).
 * Backend chip + small 左/右 tag + tappable breadcrumb that auto-scrolls to the end so long paths
 * keep the deepest segment visible; long-press opens the path menu.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TopPathBar(host: PaneHost, modifier: Modifier = Modifier) = PathCrumbs(host, host.panes[host.activePane], showSide = true, modifier = modifier)

/** Backend chip + breadcrumb of [pane]; [showSide] adds the 左/右 tag (single top bar mode). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PathCrumbs(host: PaneHost, pane: PaneState, showSide: Boolean, modifier: Modifier = Modifier) {
    val tab = pane.tab
    val active = host.activePane == pane.index
    val scroll = rememberScrollState()
    LaunchedEffect(host.activePane, tab.fsId, tab.path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val fg = MaterialTheme.colorScheme.onSurface
    val accent = if (active || showSide) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (showSide) {
            Text(
                if (pane.index == 0) t("左", "L") else t("右", "R"), fontSize = 10.sp, color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.background(accent, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(3.dp))
        }
        Text(
            host.fsLabel(tab.fsId), fontSize = 10.sp, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 72.dp)
                .background(accent.copy(alpha = 0.14f), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
        Row(
            Modifier.weight(1f).horizontalScroll(scroll).padding(start = 2.dp)
                .combinedClickable(onClick = {}, onLongClick = { host.onPathLongPress(pane) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parts = tab.path.trim('/').split('/').filter { it.isNotEmpty() }
            Text("/", Modifier.clickable { host.navigateTo(pane, "/") }.padding(horizontal = 3.dp, vertical = 8.dp), fontSize = 13.sp, color = fg)
            var acc = ""
            parts.forEachIndexed { i, part ->
                acc += "/$part"
                val target = acc
                val last = i == parts.size - 1
                Text(
                    part + if (!last) " /" else "",
                    Modifier.clickable { host.navigateTo(pane, target) }.padding(horizontal = 2.dp, vertical = 8.dp),
                    fontSize = 13.sp, maxLines = 1, color = if (last) accent else fg,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
private fun BasicFilterField(tab: TabState, active: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(PaneUi.filterFocusRequest) {
        if (PaneUi.filterFocusRequest > 0 && active) runCatching { focus.requestFocus() }
    }
    DisposableEffect(Unit) { onDispose { PaneUi.textFocused = false } }
    Surface(color = MaterialTheme.colorScheme.surface) {
        androidx.compose.foundation.text.BasicTextField(
            value = tab.filter, onValueChange = { tab.filter = it }, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(focus)
                .onFocusChanged { PaneUi.textFocused = it.isFocused },
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileList(host: PaneHost, pane: PaneState, tab: TabState, wide: Boolean, dragAndDrop: Boolean) {
    val entries = visibleEntries(host.prefs, tab)
    val state = tab.listState
    val gridState = tab.gridState
    val grid = host.prefs.gridView
    val spec = LocalDensitySpec.current
    val showThumbs = host.prefs.thumbnails
    val ctx = LocalContext.current
    val parentOffset = if (tab.path != "/") 1 else 0
    LaunchedEffect(tab.highlight, entries.size) {
        val h = tab.highlight ?: return@LaunchedEffect
        val idx = entries.indexOfFirst { it.path == h }
        if (idx >= 0 && state.firstVisibleItemIndex == 0 && !grid) state.scrollToItem((idx - 3).coerceAtLeast(0))
    }
    // Keyboard cursor: keep the highlighted row visible.
    LaunchedEffect(tab.scrollRequest) {
        if (tab.scrollRequest == 0) return@LaunchedEffect
        val idx = entries.indexOfFirst { it.path == tab.highlight }.takeIf { it >= 0 }?.plus(parentOffset) ?: return@LaunchedEffect
        if (grid) {
            val vis = gridState.layoutInfo.visibleItemsInfo
            if (vis.isEmpty() || idx <= vis.first().index || idx >= vis.last().index) gridState.scrollToItem((idx - 2).coerceAtLeast(0))
        } else {
            val vis = state.layoutInfo.visibleItemsInfo
            if (vis.isEmpty() || idx <= vis.first().index) state.scrollToItem(idx)
            else if (idx >= vis.last().index) state.scrollToItem((idx - vis.size + 2).coerceAtLeast(0))
        }
    }
    fun dragData(e: FileEntry): DragAndDropTransferData {
        val items = if (e.path in tab.selected) tab.selectedEntries() else listOf(e)
        val uris = items.filter { !it.isDirectory }.mapNotNull { host.contentUri(tab.fsId, it) }
        val clip = if (uris.isNotEmpty()) {
            ClipData(ClipDescription(items.first().name, arrayOf("*/*")), ClipData.Item(uris[0])).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        } else ClipData.newPlainText(items.first().name, items.joinToString("\n") { it.path })
        val flags = if (uris.isNotEmpty()) android.view.View.DRAG_FLAG_GLOBAL or android.view.View.DRAG_FLAG_GLOBAL_URI_READ else 0
        return DragAndDropTransferData(clip, DragPayload(pane.index, tab.fsId, items), flags)
    }
    Box(
        Modifier.fillMaxSize()
            // Right-click on empty space: the pane's "more" menu (rows consume their own right-clicks).
            .pointerInput(pane.index) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Main)
                        if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed && ev.changes.none { it.isConsumed }) {
                            ev.changes.forEach { it.consume() }
                            host.onPaneContextMenu(pane)
                        }
                    }
                }
            },
    ) {
        if (grid) {
            LazyVerticalGrid(state = gridState, columns = GridCells.Adaptive(spec.gridCell), modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(4.dp)) {
                if (tab.path != "/") item(key = "..") {
                    ParentRow { host.navigateTo(pane, com.ghostpanter.files.core.VPath.parent(tab.path)) }
                }
                items(entries.size, key = { entries[it].path }) { i ->
                    val e = entries[i]
                    val sel = e.path in tab.selected
                    FileRow(
                        e = e, selecting = tab.selecting, selected = sel,
                        highlighted = e.path == tab.highlight, wide = false,
                        onClick = { host.onEntryClick(pane, e) },
                        onLongClick = { host.onEntryLongPress(pane, e) },
                        onSwipe = { host.onEntrySwipe(pane, e) },
                        thumb = { c -> host.thumbnail(tab.fsId, e, spec.gridThumbPx, c) },
                        iconSize = spec.gridIcon, showThumbs = showThumbs, spec = spec,
                        dragData = if (dragAndDrop) ({ dragData(e) }) else null,
                        touchDrag = dragAndDrop && tab.selecting && sel,
                    )
                }
            }
        } else LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
            if (tab.path != "/") item(key = "..") {
                ParentRow { host.navigateTo(pane, com.ghostpanter.files.core.VPath.parent(tab.path)) }
            }
            items(entries.size, key = { entries[it].path }) { i ->
                val e = entries[i]
                val sel = e.path in tab.selected
                FileRow(
                    e = e,
                    selecting = tab.selecting,
                    selected = sel,
                    highlighted = e.path == tab.highlight,
                    wide = wide,
                    onClick = { host.onEntryClick(pane, e) },
                    onLongClick = { host.onEntryLongPress(pane, e) },
                    onSwipe = { host.onEntrySwipe(pane, e) },
                    thumb = { c -> host.thumbnail(tab.fsId, e, spec.thumbPx, c) },
                    iconSize = spec.icon, showThumbs = showThumbs, spec = spec,
                    dragData = if (dragAndDrop) ({ dragData(e) }) else null,
                    touchDrag = dragAndDrop && tab.selecting && sel,
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
    thumb: ((cancelled: () -> Boolean) -> android.graphics.Bitmap?)? = null,
    iconSize: Dp = 28.dp,
    showThumbs: Boolean = true,
    spec: DensitySpec = densitySpec("normal"),
    /** Tablet frame: drag source (mouse drag; touch long-press-drag on selected rows). */
    dragData: (() -> DragAndDropTransferData?)? = null,
    touchDrag: Boolean = false,
) {
    val bg = when {
        selected -> MaterialTheme.colorScheme.primaryContainer
        highlighted -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    val swipePx = with(LocalDensity.current) { 40.dp.toPx() }
    val shadow = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val latestLong by rememberUpdatedState(onLongClick)
    val latestDrag by rememberUpdatedState(dragData)
    Row(
        Modifier.fillMaxWidth().background(bg)
            // Mouse right-click: same as long-press (context menu).
            .pointerInput(e.path) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        if (ev.type == PointerEventType.Press && ev.buttons.isSecondaryPressed) {
                            ev.changes.forEach { it.consume() }
                            latestLong()
                        }
                    }
                }
            }
            // MT: a horizontal swipe on a row toggles its selection (enters multi-select).
            .then(if (touchDrag) Modifier else Modifier.pointerInput(e.path, dragData != null) {
                var acc = 0f
                var fired = false
                var mouse = false
                detectHorizontalDragGestures(
                    onDragStart = { acc = 0f; fired = false },
                    onHorizontalDrag = { change, d ->
                        mouse = change.type == PointerType.Mouse && dragData != null
                        if (!mouse) {
                            acc += d
                            if (!fired && kotlin.math.abs(acc) > swipePx) { fired = true; onSwipe() }
                            change.consume()
                        }
                    },
                )
            })
            .combinedClickable(onClick = onClick, onLongClick = if (touchDrag) null else onLongClick)
            .then(if (dragData == null) Modifier else Modifier.dragAndDropSource(drawDragDecoration = { drawRect(shadow) }) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.type == PointerType.Mouse) {
                        if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                        val moved = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                        if (moved != null) latestDrag?.invoke()?.let { startTransfer(it) }
                    } else if (touchDrag) {
                        val lp = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        val moved = awaitTouchSlopOrCancellation(lp.id) { c, _ -> c.consume() }
                        if (moved != null) latestDrag?.invoke()?.let { startTransfer(it) } else latestLong()
                    }
                }
            })
            .padding(start = 6.dp, end = 4.dp, top = spec.rowPad, bottom = spec.rowPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = iconFor(e)
        var thumbBmp by remember(e.path, e.lastModified, showThumbs) { mutableStateOf<android.graphics.Bitmap?>(null) }
        LaunchedEffect(e.path, e.lastModified, showThumbs) {
            if (!showThumbs || e.isDirectory || thumb == null) { thumbBmp = null; return@LaunchedEffect }
            thumbBmp = withContext(Dispatchers.IO) {
                val job = coroutineContext[kotlinx.coroutines.Job]
                runCatching { thumb { job?.isActive == false } }.getOrNull()
            }
        }
        Box(Modifier.size(iconSize)) {
            val tb = thumbBmp
            if (tb != null) Image(tb.asImageBitmap(), null, Modifier.size(iconSize).clip(RoundedCornerShape(3.dp)), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
            if (e.isLink) Icon(Icons.Filled.Link, null, Modifier.size(12.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.tertiary)
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = spec.nameSp.sp, lineHeight = (spec.nameSp + 2).sp,
                color = if (e.isHidden) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f) else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTimeShort(e.lastModified), fontSize = spec.metaSp.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
                Spacer(Modifier.weight(1f))
                if (wide) e.permissions?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = spec.metaSp.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(end = 6.dp), maxLines = 1)
                }
                if (!e.isDirectory) Text(formatSize(e.size), fontSize = spec.metaSp.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1)
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
