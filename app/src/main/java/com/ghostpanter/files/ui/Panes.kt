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
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.ui.draw.drawBehind
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
        val divider = 8.dp
        val leftW = (total - divider) * r
        val rightW = total - divider - leftW
        Row(Modifier.fillMaxSize()) {
            PaneView(host, host.panes[0], leftW, Modifier.width(leftW), perPaneHeader, dragAndDrop)
            SplitHandle(
                onDelta = { delta -> onRatio((r + delta / totalPx).coerceIn(0.2f, 0.8f)) },
                onDone = onRatioDone,
                onReset = { onRatio(0.5f); onRatioDone() },
                width = divider,
                subtle = true,
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
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(PANE_RADIUS)
    Column(
        modifier.fillMaxHeight()
            .clip(shape)
            .background(if (active) cs.surface else Ghost.colors.paneIdle)
            .border(if (active || dropHover) 1.5.dp else 1.dp, if (active || dropHover) cs.primary else cs.outlineVariant, shape)
            // Any touch inside a pane makes it the active one (MT behaviour), without consuming the event.
            .pointerInput(pane.index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    host.activePane = pane.index
                }
            }
            .then(if (dragAndDrop) Modifier.dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget) else Modifier)
            .then(if (dropHover) Modifier.background(cs.primary.copy(alpha = 0.06f)) else Modifier),
    ) {
        val tab = pane.tab
        if (pane.tabs.size > 1) TabStrip(host, pane, active) else PaneHeader(host, pane, active)
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

/** Corner radius of the pane cards. */
val PANE_RADIUS = 16.dp

/** Small header of a pane card: backend · folder; the focused pane gets the accent + a dot. */
@Composable
private fun PaneHeader(host: PaneHost, pane: PaneState, active: Boolean) {
    val cs = MaterialTheme.colorScheme
    val tab = pane.tab
    Row(
        Modifier.fillMaxWidth().height(30.dp).padding(start = 12.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            host.fsLabel(tab.fsId), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (active) cs.primary else cs.onSurfaceVariant,
        )
        Text(
            "  ·  " + tab.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = cs.outline, modifier = Modifier.weight(1f),
        )
        if (active) Box(Modifier.size(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(cs.primary))
    }
}

@Composable
private fun TabStrip(host: PaneHost, pane: PaneState, active: Boolean) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(34.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pane.tabs.forEachIndexed { i, tb ->
            val sel = i == pane.activeTab
            Row(
                Modifier.padding(end = 4.dp).height(24.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (sel) (if (active) cs.primaryContainer else cs.surfaceVariant) else Color.Transparent)
                    .clickable { host.selectTab(pane, i) }
                    .padding(start = 8.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tb.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                    color = if (sel && active) cs.primary else cs.onSurfaceVariant,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
                Icon(Icons.Rounded.Close, t("关闭", "Close"), Modifier.size(18.dp).clickable { host.closeTab(pane, i) }.padding(3.dp), tint = cs.outline)
            }
        }
    }
}

/** UI state shared between the top bar and the panes. */
object PaneUi {
    var filterOpen by mutableStateOf(false)
    /** A text field inside the frame has focus: keyboard shortcuts must not steal its keys. */
    var textFocused by mutableStateOf(false)
    /** Set by Ctrl+F / the tablet filter button: the focused pane's filter field takes focus once. */
    var filterFocusPending by mutableStateOf(false)
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

/** Backend + breadcrumb of [pane] as one rounded pill (HyperOS search-field look). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PathCrumbs(host: PaneHost, pane: PaneState, showSide: Boolean, modifier: Modifier = Modifier) {
    val tab = pane.tab
    val scroll = rememberScrollState()
    LaunchedEffect(host.activePane, tab.fsId, tab.path, scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    val cs = MaterialTheme.colorScheme
    Row(
        modifier.height(38.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant).padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            host.fsLabel(tab.fsId), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.primary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 110.dp),
        )
        Row(
            Modifier.weight(1f).horizontalScroll(scroll).padding(start = 2.dp)
                .combinedClickable(onClick = {}, onLongClick = { host.onPathLongPress(pane) }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val parts = tab.path.trim('/').split('/').filter { it.isNotEmpty() }
            Sep()
            Text("/", Modifier.clickable { host.navigateTo(pane, "/") }.padding(horizontal = 2.dp, vertical = 9.dp), fontSize = 13.sp,
                color = if (parts.isEmpty()) cs.onSurface else cs.onSurfaceVariant)
            var acc = ""
            parts.forEachIndexed { i, part ->
                acc += "/$part"
                val target = acc
                val last = i == parts.size - 1
                if (i > 0) Sep()
                Text(
                    part,
                    Modifier.clickable { host.navigateTo(pane, target) }.padding(horizontal = 2.dp, vertical = 9.dp),
                    fontSize = 13.sp, maxLines = 1,
                    color = if (last) cs.onSurface else cs.onSurfaceVariant,
                    fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            Spacer(Modifier.width(6.dp))
        }
    }
}

@Composable
private fun Sep() = Text("/", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 3.dp))

@Composable
private fun BasicFilterField(tab: TabState, active: Boolean) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(PaneUi.filterFocusPending, active) {
        if (PaneUi.filterFocusPending && active) { PaneUi.filterFocusPending = false; runCatching { focus.requestFocus() } }
    }
    DisposableEffect(Unit) { onDispose { PaneUi.textFocused = false } }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)) {
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
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
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
    // Package-named folders get their app's icon; probe once per listing, not per row.
    val appParent = remember(tab.fsId, tab.path, entries.size) {
        entries.any { it.isDirectory && host.appPackage(tab.fsId, tab.path, it) != null }
    }
    LaunchedEffect(tab.highlight, entries.size) {
        val h = tab.highlight ?: return@LaunchedEffect
        val idx = entries.indexOfFirst { it.path == h }
        if (idx < 0 || grid || state.firstVisibleItemIndex != 0 || state.firstVisibleItemScrollOffset != 0) return@LaunchedEffect
        // Wait for the first layout, then scroll only when the highlighted row is not already fully
        // visible. Scrolling unconditionally clamped at the list end and left the 「上一级」 row cut
        // off under the pane header on tall (tablet) panes.
        val info = androidx.compose.runtime.snapshotFlow { state.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
        val target = idx + parentOffset
        val row = info.visibleItemsInfo.firstOrNull { it.index == target }
        val fullyVisible = row != null && row.offset >= info.viewportStartOffset && row.offset + row.size <= info.viewportEndOffset
        if (!fullyVisible) state.scrollToItem((target - 3).coerceAtLeast(0))
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
                        cachedThumb = { host.cachedThumbnail(tab.fsId, e, spec.gridThumbPx) },
                        iconSize = spec.gridIcon, showThumbs = showThumbs, spec = spec,
                        dragData = if (dragAndDrop) ({ dragData(e) }) else null,
                        touchDrag = dragAndDrop && tab.selecting && sel,
                        appPkg = if (appParent) host.appPackage(tab.fsId, tab.path, e) else null,
                        appCached = { p -> host.cachedApp(tab.fsId, p) },
                        appLoad = { p -> host.loadApp(tab.fsId, p) },
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
                    divider = i > 0 || tab.path != "/",
                    selecting = tab.selecting,
                    selected = sel,
                    highlighted = e.path == tab.highlight,
                    wide = wide,
                    onClick = { host.onEntryClick(pane, e) },
                    onLongClick = { host.onEntryLongPress(pane, e) },
                    onSwipe = { host.onEntrySwipe(pane, e) },
                    thumb = { c -> host.thumbnail(tab.fsId, e, spec.thumbPx, c) },
                    cachedThumb = { host.cachedThumbnail(tab.fsId, e, spec.thumbPx) },
                    iconSize = spec.icon, showThumbs = showThumbs, spec = spec,
                    dragData = if (dragAndDrop) ({ dragData(e) }) else null,
                    touchDrag = dragAndDrop && tab.selecting && sel,
                    appPkg = if (appParent) host.appPackage(tab.fsId, tab.path, e) else null,
                    appCached = { p -> host.cachedApp(tab.fsId, p) },
                    appLoad = { p -> host.loadApp(tab.fsId, p) },
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.DriveFolderUpload, null, tint = Ghost.colors.folder.copy(alpha = 0.8f), modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Text(t("上一级", "Up"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    e: FileEntry,
    selecting: Boolean,
    /** Hairline above the row (list mode, all but the first row). */
    divider: Boolean = false,
    selected: Boolean,
    highlighted: Boolean,
    wide: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipe: () -> Unit,
    thumb: ((cancelled: () -> Boolean) -> android.graphics.Bitmap?)? = null,
    cachedThumb: (() -> android.graphics.Bitmap?)? = null,
    iconSize: Dp = 28.dp,
    showThumbs: Boolean = true,
    spec: DensitySpec = densitySpec("normal"),
    /** Tablet frame: drag source (mouse drag; touch long-press-drag on selected rows). */
    dragData: (() -> DragAndDropTransferData?)? = null,
    touchDrag: Boolean = false,
    /** App folder: package name, plus cached / async lookups of its label and icon. */
    appPkg: String? = null,
    appCached: ((String) -> AppMeta?)? = null,
    appLoad: (suspend (String) -> AppMeta?)? = null,
) {
    val cs = MaterialTheme.colorScheme
    var app by remember(appPkg) { mutableStateOf(appPkg?.let { appCached?.invoke(it) }) }
    if (appPkg != null && app == null && appLoad != null) LaunchedEffect(appPkg) { app = appLoad(appPkg) }
    val bg = when {
        selected -> cs.primaryContainer
        highlighted -> cs.surfaceVariant
        else -> Color.Transparent
    }
    val line = cs.outlineVariant
    val inset = with(LocalDensity.current) { (iconSize + 20.dp).toPx() }
    val swipePx = with(LocalDensity.current) { 40.dp.toPx() }
    val shadow = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val latestLong by rememberUpdatedState(onLongClick)
    val latestDrag by rememberUpdatedState(dragData)
    Row(
        Modifier.fillMaxWidth()
            .then(if (divider && !selected) Modifier.drawBehind {
                drawLine(line, androidx.compose.ui.geometry.Offset(inset, 0f), androidx.compose.ui.geometry.Offset(size.width, 0f), 1f)
            } else Modifier)
            .background(bg)
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
            .padding(start = 10.dp, end = 8.dp, top = spec.rowPad + 2.dp, bottom = spec.rowPad + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val (icon, tint) = iconFor(e)
        var thumbBmp by remember(e.path, e.lastModified, showThumbs) {
            mutableStateOf(if (showThumbs && !e.isDirectory) cachedThumb?.invoke() else null)
        }
        LaunchedEffect(e.path, e.lastModified, showThumbs) {
            if (!showThumbs || e.isDirectory || thumb == null) { thumbBmp = null; return@LaunchedEffect }
            if (thumbBmp != null) return@LaunchedEffect
            thumbBmp = withContext(Dispatchers.IO) {
                val job = coroutineContext[kotlinx.coroutines.Job]
                runCatching { thumb { job?.isActive == false } }.getOrNull()
            }
        }
        Box(Modifier.size(iconSize)) {
            val tb = thumbBmp
            val appIcon = app?.icon
            if (appIcon != null) {
                Image(appIcon.asImageBitmap(), null, Modifier.size(iconSize).padding(iconSize / 14))
                Icon(
                    Icons.Rounded.Folder, null,
                    Modifier.size(iconSize * 0.46f).align(Alignment.BottomEnd)
                        .background(cs.surface, RoundedCornerShape(iconSize / 8)).padding(1.dp),
                    tint = tint,
                )
            } else if (tb != null) {
                val apk = e.extension.lowercase() in setOf("apk", "apks", "xapk", "apkm")
                Image(
                    tb.asImageBitmap(), null,
                    Modifier.size(iconSize).clip(RoundedCornerShape(if (apk) 0.dp else iconSize / 4)),
                    contentScale = if (apk) androidx.compose.ui.layout.ContentScale.Fit else androidx.compose.ui.layout.ContentScale.Crop,
                )
                if (e.extension.lowercase() in VIDEO_EXT) Icon(
                    Icons.Rounded.PlayArrow, null,
                    Modifier.size(iconSize * 0.5f).align(Alignment.Center).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)).padding(1.dp),
                    tint = Color.White,
                )
            } else Icon(icon, null, tint = tint, modifier = Modifier.size(iconSize))
            if (e.isLink) Icon(Icons.Rounded.Link, null, Modifier.size(12.dp).align(Alignment.BottomEnd), tint = cs.tertiary)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                e.name, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = spec.nameSp.sp, lineHeight = (spec.nameSp + 3).sp,
                fontWeight = FontWeight.Medium,
                color = if (e.isHidden) cs.onSurface.copy(alpha = 0.5f) else cs.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    run {
                        val time = if (wide) formatTimeShort(e.lastModified) else formatTimeCompact(e.lastModified)
                        val a = app
                        when {
                            a != null && appPkg != null -> a.label + " · " + appPkg
                            e.isDirectory -> time
                            else -> formatSize(e.size) + " · " + time
                        }
                    },
                    fontSize = spec.metaSp.sp, color = cs.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (wide) e.permissions?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, fontSize = spec.metaSp.sp, color = cs.outline, maxLines = 1, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                }
            }
        }
        if (selecting) {
            Icon(
                if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                Modifier.padding(start = 4.dp).size(20.dp),
                tint = if (selected) cs.primary else cs.outline.copy(alpha = 0.6f),
            )
        }
    }
}

@Composable
fun iconFor(e: FileEntry): Pair<ImageVector, Color> {
    val c = Ghost.colors
    if (e.isDirectory) return Icons.Rounded.Folder to c.folder
    return when (e.extension.lowercase()) {
        in IMAGE_EXT -> Icons.Rounded.Image to c.image
        in VIDEO_EXT -> Icons.Rounded.Movie to c.video
        in AUDIO_EXT -> Icons.Rounded.MusicNote to c.audio
        "apk", "apks", "xapk", "apkm" -> Icons.Rounded.Android to c.apk
        "zip", "7z", "rar", "tar", "gz", "xz", "bz2", "tgz" -> Icons.Rounded.FolderZip to c.archive
        in TEXT_EXT, in PREVIEW_MD -> Icons.Rounded.Description to c.text
        "pdf" -> Icons.Rounded.PictureAsPdf to c.pdf
        else -> Icons.AutoMirrored.Rounded.InsertDriveFile to c.other
    }
}
