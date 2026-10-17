package com.ghostpanter.files.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SortBy { NAME, SIZE, TIME, TYPE }

/** The view options the file panes need (implemented by [Settings], faked in screenshot tests). */
interface ViewPrefs {
    val showHidden: Boolean
    val foldersFirst: Boolean
    val ascending: Boolean
    val sortBy: SortBy
    /** Grid instead of list. */
    val gridView: Boolean get() = false
    /** "compact" | "normal" | "large" (see [com.ghostpanter.files.ui.DensitySpec]). */
    val density: String get() = "normal"
    val thumbnails: Boolean get() = true
}

class Settings(context: Context) : ViewPrefs {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = mutableStateOf(p.getBoolean(key, def))

    private val _showHidden = bool("show_hidden", false)
    override var showHidden: Boolean
        get() = _showHidden.value
        set(v) { _showHidden.value = v; p.edit().putBoolean("show_hidden", v).apply() }

    private val _foldersFirst = bool("folders_first", true)
    override var foldersFirst: Boolean
        get() = _foldersFirst.value
        set(v) { _foldersFirst.value = v; p.edit().putBoolean("folders_first", v).apply() }

    private val _ascending = bool("ascending", true)
    override var ascending: Boolean
        get() = _ascending.value
        set(v) { _ascending.value = v; p.edit().putBoolean("ascending", v).apply() }

    private val _recycleBin = bool("recycle_bin", false)
    var recycleBin: Boolean
        get() = _recycleBin.value
        set(v) { _recycleBin.value = v; p.edit().putBoolean("recycle_bin", v).apply() }

    private val _sortBy = mutableStateOf(runCatching { SortBy.valueOf(p.getString("sort_by", "NAME")!!) }.getOrDefault(SortBy.NAME))
    override var sortBy: SortBy
        get() = _sortBy.value
        set(v) { _sortBy.value = v; p.edit().putString("sort_by", v.name).apply() }

    private val _paneRatio = mutableStateOf(p.getFloat("pane_ratio", 0.5f))
    var paneRatio: Float
        get() = _paneRatio.value
        set(v) { _paneRatio.value = v; p.edit().putFloat("pane_ratio", v).apply() }

    var concurrentJobs: Int
        get() = p.getInt("concurrent_jobs", 2)
        set(v) { p.edit().putInt("concurrent_jobs", v).apply() }

    /** Jobs at once against the same server (each has its own connection). Default 1. */
    var jobsPerServer: Int
        get() = p.getInt("jobs_per_server", 1)
        set(v) { p.edit().putInt("jobs_per_server", v).apply() }

    /** Persisted tab locations: "fsId\u0001path" per tab, per pane. */
    fun saveTabs(pane: Int, tabs: List<Pair<String, String>>, active: Int) {
        p.edit()
            .putString("tabs_$pane", tabs.joinToString("\u0002") { it.first + "\u0001" + it.second })
            .putInt("tab_active_$pane", active)
            .apply()
    }

    fun loadTabs(pane: Int): Pair<List<Pair<String, String>>, Int> {
        val s = p.getString("tabs_$pane", null) ?: return emptyList<Pair<String, String>>() to 0
        val tabs = s.split('\u0002').mapNotNull {
            val parts = it.split('\u0001'); if (parts.size == 2) parts[0] to parts[1] else null
        }
        return tabs to p.getInt("tab_active_$pane", 0)
    }

    fun safTrees(): List<String> = p.getStringSet("saf_trees", emptySet())!!.toList().sorted()
    fun addSafTree(uri: String) { p.edit().putStringSet("saf_trees", safTrees().toSet() + uri).apply() }
    fun removeSafTree(uri: String) { p.edit().putStringSet("saf_trees", safTrees().toSet() - uri).apply() }

    private val _showThumbnails = bool("show_thumbnails", true)
    var showThumbnails: Boolean
        get() = _showThumbnails.value
        set(v) { _showThumbnails.value = v; p.edit().putBoolean("show_thumbnails", v).apply() }

    // State-backed so toggling list/grid recomposes the panes at once.
    private val _viewMode = mutableStateOf(p.getString("view_mode", "LIST") ?: "LIST")
    var viewMode: String
        get() = _viewMode.value
        set(v) { _viewMode.value = v; p.edit().putString("view_mode", v).apply() }
    override val gridView: Boolean get() = viewMode == "GRID"
    override val density: String get() = listDensity
    override val thumbnails: Boolean get() = showThumbnails

    /** system | light | dark — state so theme recomposes when toggled. */
    private val _themeMode = mutableStateOf(p.getString("theme_mode", "system") ?: "system")
    var themeMode: String
        get() = _themeMode.value
        set(v) { _themeMode.value = v; p.edit().putString("theme_mode", v).apply() }

    /** When true and dark theme is active, use pure black (#000000) surfaces for OLED. Default off. */
    private val _language = mutableStateOf(p.getString("language", "system") ?: "system")
    /** "system" | "zh" | "en". */
    var language: String
        get() = _language.value
        set(v) { _language.value = v; p.edit().putString("language", v).apply(); com.ghostpanter.files.core.Lang.apply(v) }

    private val _vlcHw = bool("vlc_hw_decode", true)
    var vlcHwDecode: Boolean
        get() = _vlcHw.value
        set(v) { _vlcHw.value = v; p.edit().putBoolean("vlc_hw_decode", v).apply() }

    private val _oledBlack = bool("oled_black", false)
    var oledBlack: Boolean
        get() = _oledBlack.value
        set(v) { _oledBlack.value = v; p.edit().putBoolean("oled_black", v).apply() }

    // ---------------------------------------------------------------- tablet / layout
    private fun str(key: String, def: String) = mutableStateOf(p.getString(key, def) ?: def)

    /** "auto" | "phone" | "tablet" — like scrcpy-android's layout switch. */
    private val _layoutMode = str("layout_mode", "auto")
    var layoutMode: String
        get() = _layoutMode.value
        set(v) { _layoutMode.value = v; p.edit().putString("layout_mode", v).apply() }

    /** File list density: "compact" | "normal" (phone default, unchanged) | "large". */
    private val _density = str("list_density", "normal")
    var listDensity: String
        get() = _density.value
        set(v) { _density.value = v; p.edit().putString("list_density", v).apply() }

    /** Tablet: persistent side panel (bookmarks / storages / servers) expanded next to the rail. */
    private val _sidePanel = bool("tablet_side_panel", true)
    var tabletSidePanel: Boolean
        get() = _sidePanel.value
        set(v) { _sidePanel.value = v; p.edit().putBoolean("tablet_side_panel", v).apply() }

    /** Tablet: third details/preview panel on very wide windows. */
    private val _previewPanel = bool("tablet_preview_panel", true)
    var tabletPreviewPanel: Boolean
        get() = _previewPanel.value
        set(v) { _previewPanel.value = v; p.edit().putBoolean("tablet_preview_panel", v).apply() }

    private val _previewRatio = mutableStateOf(p.getFloat("preview_width_dp", 300f))
    var previewWidthDp: Float
        get() = _previewRatio.value
        set(v) { _previewRatio.value = v; p.edit().putFloat("preview_width_dp", v).apply() }
}
