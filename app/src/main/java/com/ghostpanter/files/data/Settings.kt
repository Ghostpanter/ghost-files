package com.ghostpanter.files.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class SortBy { NAME, SIZE, TIME, TYPE }

class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private fun bool(key: String, def: Boolean) = mutableStateOf(p.getBoolean(key, def))

    private val _showHidden = bool("show_hidden", false)
    var showHidden: Boolean
        get() = _showHidden.value
        set(v) { _showHidden.value = v; p.edit().putBoolean("show_hidden", v).apply() }

    private val _foldersFirst = bool("folders_first", true)
    var foldersFirst: Boolean
        get() = _foldersFirst.value
        set(v) { _foldersFirst.value = v; p.edit().putBoolean("folders_first", v).apply() }

    private val _ascending = bool("ascending", true)
    var ascending: Boolean
        get() = _ascending.value
        set(v) { _ascending.value = v; p.edit().putBoolean("ascending", v).apply() }

    private val _recycleBin = bool("recycle_bin", false)
    var recycleBin: Boolean
        get() = _recycleBin.value
        set(v) { _recycleBin.value = v; p.edit().putBoolean("recycle_bin", v).apply() }

    private val _sortBy = mutableStateOf(runCatching { SortBy.valueOf(p.getString("sort_by", "NAME")!!) }.getOrDefault(SortBy.NAME))
    var sortBy: SortBy
        get() = _sortBy.value
        set(v) { _sortBy.value = v; p.edit().putString("sort_by", v.name).apply() }

    var concurrentJobs: Int
        get() = p.getInt("concurrent_jobs", 2)
        set(v) { p.edit().putInt("concurrent_jobs", v).apply() }

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
}
