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
    var selected by mutableStateOf<Set<String>>(emptySet())
    /** MT-style multi-select mode (stays on even when nothing is ticked yet). */
    var multiSelect by mutableStateOf(false)
    var filter by mutableStateOf("")
    var highlight by mutableStateOf<String?>(null)
    val history = ArrayDeque<Pair<String, String>>()
    val forward = ArrayDeque<Pair<String, String>>()
    val scroll = HashMap<String, Pair<Int, Int>>()
    var listState by mutableStateOf(LazyListState())
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
    fun thumbnail(fsId: String, e: FileEntry): android.graphics.Bitmap? = null
}

/** One row of an MT-style action list (long-press menu, "more" menu). */
data class MenuAction(val label: String, val icon: ImageVector, val hint: String? = null, val enabled: Boolean = true, val run: () -> Unit)

data class ActionMenu(val title: String, val subtitle: String?, val actions: List<MenuAction>)

/** Bottom toolbar buttons. */
enum class Tool { BACK, FORWARD, REFRESH, SYNC, MULTI, NEW, UP, MORE, SELECT_ALL, INVERT, SELECT_TYPE, COPY_OTHER, MOVE_OTHER, DELETE, SEL_MORE, DONE, PASTE }
