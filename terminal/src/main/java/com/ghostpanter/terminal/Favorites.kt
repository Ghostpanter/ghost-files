package com.ghostpanter.terminal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 命令收藏: named commands shared by every terminal tab (local and ADB). JSON import / export. */
data class Favorite(val name: String, val command: String)

object FavoritesCodec {
    fun encode(list: List<Favorite>): String = JSONObject()
        .put("format", "ghost-files-terminal-favorites")
        .put("version", 1)
        .put("items", JSONArray().apply { list.forEach { put(JSONObject().put("name", it.name).put("command", it.command)) } })
        .toString(2)

    /** Accepts our own export, a bare JSON array of {name, command}, or plain text (one command per line). */
    fun decode(text: String): List<Favorite> {
        val t = text.trim()
        if (t.isEmpty()) return emptyList()
        val arr = runCatching {
            when {
                t.startsWith("{") -> JSONObject(t).getJSONArray("items")
                t.startsWith("[") -> JSONArray(t)
                else -> null
            }
        }.getOrNull()
        if (arr == null) return t.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { Favorite(it.take(40), it) }
        val out = ArrayList<Favorite>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val cmd = o.optString("command", o.optString("cmd", "")).trim()
            if (cmd.isEmpty()) continue
            out += Favorite(o.optString("name", cmd.take(40)).ifBlank { cmd.take(40) }, cmd)
        }
        return out
    }

    fun merge(old: List<Favorite>, add: List<Favorite>): List<Favorite> {
        val seen = old.map { it.command }.toMutableSet()
        return old + add.filter { seen.add(it.command) }
    }
}

class FavoritesStore(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("terminal", Context.MODE_PRIVATE)

    fun load(): List<Favorite> = runCatching { FavoritesCodec.decode(p.getString(KEY, null) ?: "") }.getOrDefault(emptyList())
        .ifEmpty { if (p.contains(KEY)) emptyList() else DEFAULTS }

    fun save(list: List<Favorite>) { p.edit().putString(KEY, FavoritesCodec.encode(list)).apply() }

    var fontSp: Float
        get() = p.getFloat("font_sp", 13f)
        set(v) { p.edit().putFloat("font_sp", v.coerceIn(MIN_SP, MAX_SP)).apply() }

    companion object {
        private const val KEY = "favorites"
        const val MIN_SP = 8f
        const val MAX_SP = 28f
        val DEFAULTS = listOf(
            Favorite("getprop model", "getprop ro.product.model"),
            Favorite("df -h", "df -h"),
            Favorite("top -n 1", "top -n 1 -m 15"),
            Favorite("dumpsys battery", "dumpsys battery"),
            Favorite("pm list packages -3", "pm list packages -3"),
            Favorite("wm size / density", "wm size; wm density"),
        )
    }
}
