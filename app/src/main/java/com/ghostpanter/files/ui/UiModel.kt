package com.ghostpanter.files.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.ghostpanter.files.core.FileEntry
import com.ghostpanter.files.core.VPath
import com.ghostpanter.files.data.ViewPrefs
import kotlinx.coroutines.Job

class TabState(fsId: String, path: String) {
    var fsId by mutableStateOf(fsId)
    var path by mutableStateOf(path)
    var entries by mutableStateOf<List<FileEntry>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** Raw exception text behind [error], shown under 「详情」. */
    var errorDetail by mutableStateOf<String?>(null)
    var selected by mutableStateOf<Set<String>>(emptySet())
    /** MT-style multi-select mode (stays on even when nothing is ticked yet). */
    var multiSelect by mutableStateOf(false)
    /** MT 连选锚点：上一次滑动选中的文件路径（见 [swipeSelect]）。 */
    var swipeAnchor: String? = null
    var filter by mutableStateOf("")
    var highlight by mutableStateOf<String?>(null)
    val history = ArrayDeque<Pair<String, String>>()
    val forward = ArrayDeque<Pair<String, String>>()
    val scroll = HashMap<String, Pair<Int, Int>>()
    var listState by mutableStateOf(LazyListState())
    var gridState by mutableStateOf(androidx.compose.foundation.lazy.grid.LazyGridState())
    /** Bumped by keyboard navigation: the file list scrolls [highlight] into view. */
    var scrollRequest by mutableIntStateOf(0)
    var loadJob: Job? = null

    val title: String get() = VPath.name(path).ifEmpty { "/" }
    val selecting: Boolean get() = multiSelect || selected.isNotEmpty()
    fun selectedEntries() = entries.filter { it.path in selected }
}

class PaneState(val index: Int) {
    val tabs = mutableStateListOf<TabState>()
    var activeTab by mutableIntStateOf(0)
    val tab: TabState get() = tabs[activeTab.coerceIn(0, tabs.size - 1)]
}

/** Everything the dual-pane frame needs; implemented by MainViewModel and by screenshot fakes. */
interface PaneHost {
    val panes: List<PaneState>
    var activePane: Int
    val prefs: ViewPrefs
    fun fsLabel(fsId: String): String
    fun onEntryClick(pane: PaneState, e: FileEntry)
    fun onEntryLongPress(pane: PaneState, e: FileEntry)
    fun onEntrySwipe(pane: PaneState, e: FileEntry)
    fun navigateTo(pane: PaneState, path: String)
    fun onPathLongPress(pane: PaneState)
    fun selectTab(pane: PaneState, i: Int)
    fun closeTab(pane: PaneState, i: Int)
    fun reload(tab: TabState)
    /** Thumbnail for [e] on [fsId] (blocking; call off the main thread). */
    fun thumbnail(fsId: String, e: FileEntry, sizePx: Int = 96, cancelled: () -> Boolean = { false }): android.graphics.Bitmap? = null
    /** Memory-cached thumbnail, read synchronously so rows that scroll back in don't flash the type icon. */
    fun cachedThumbnail(fsId: String, e: FileEntry, sizePx: Int = 96): android.graphics.Bitmap? = null
    /** Right-click / secondary click on empty pane area. */
    fun onPaneContextMenu(pane: PaneState) {}
    /** Entries dragged from pane [fromPane] were dropped on [toPane]. */
    fun onDropEntries(fromPane: Int, entries: List<FileEntry>, toPane: PaneState) {}
    /** content:// URIs dropped from another app onto [toPane]. */
    fun onDropUris(toPane: PaneState, uris: List<android.net.Uri>) {}
    /** Small UTF-8 text preview for the details panel (blocking; off main thread). */
    fun textPreview(fsId: String, e: FileEntry): String? = null
    /** Opens [e] (also for folders), ignoring multi-select. */
    fun openEntry(pane: PaneState, e: FileEntry) {}
    /** content:// URI for dragging [e] into other apps (local files only). */
    fun contentUri(fsId: String, e: FileEntry): android.net.Uri? = null

    /** Package an app folder stands for (Android/data/<pkg>, /data/data/<pkg>…), or null. */
    fun appPackage(fsId: String, dir: String, e: FileEntry): String? = null

    /** Cached app label/icon for [pkg] on [fsId]'s device; null if not loaded (or not installed). */
    fun cachedApp(fsId: String, pkg: String): AppMeta? = null

    /** Loads label/icon off the main thread; null when the package is not installed there. */
    suspend fun loadApp(fsId: String, pkg: String): AppMeta? = null
}

/** Local drag-and-drop payload (dual-pane drag between panes). */
data class DragPayload(val fromPane: Int, val fsId: String, val entries: List<FileEntry>)

/** One row of an MT-style action list (long-press menu, "more" menu). */
data class MenuAction(val label: String, val icon: ImageVector, val hint: String? = null, val enabled: Boolean = true, val run: () -> Unit)

data class ActionMenu(val title: String, val subtitle: String?, val actions: List<MenuAction>)

/** Bottom toolbar buttons. */
enum class Tool { BACK, FORWARD, REFRESH, SYNC, MULTI, NEW, UP, MORE, SELECT_ALL, INVERT, SELECT_TYPE, COPY_OTHER, MOVE_OTHER, DELETE, SEL_MORE, DONE, PASTE }
