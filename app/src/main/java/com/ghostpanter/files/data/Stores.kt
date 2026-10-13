package com.ghostpanter.files.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import com.ghostpanter.files.core.HostKeyStore
import com.ghostpanter.files.core.Protocol
import com.ghostpanter.files.core.ServerConfig
import org.json.JSONArray
import org.json.JSONObject

class ServerStore(context: Context) {
    private val p = context.getSharedPreferences("servers", Context.MODE_PRIVATE)
    val servers = mutableStateListOf<ServerConfig>().apply { addAll(load()) }

    private fun load(): List<ServerConfig> {
        val arr = runCatching { JSONArray(p.getString("list", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            runCatching {
                ServerConfig(
                    id = o.getString("id"), name = o.getString("name"),
                    protocol = Protocol.valueOf(o.getString("protocol")),
                    host = o.getString("host"), port = o.optInt("port", 0),
                    username = o.optString("username"), password = Crypto.decrypt(o.optString("password")),
                    path = o.optString("path"), domain = o.optString("domain"),
                    encoding = o.optString("encoding", "UTF-8"), passiveMode = o.optBoolean("passive", true),
                    privateKey = Crypto.decrypt(o.optString("privateKey")), anonymous = o.optBoolean("anonymous", false),
                    jumpHost = o.optString("jumpHost"), jumpPort = o.optInt("jumpPort", 22),
                    jumpUser = o.optString("jumpUser"), jumpPassword = Crypto.decrypt(o.optString("jumpPassword")),
                    jumpPrivateKey = Crypto.decrypt(o.optString("jumpPrivateKey")),
                )
            }.getOrNull()
        }
    }

    private fun persist() {
        val arr = JSONArray()
        for (s in servers) arr.put(JSONObject().apply {
            put("id", s.id); put("name", s.name); put("protocol", s.protocol.name)
            put("host", s.host); put("port", s.port); put("username", s.username)
            put("password", Crypto.encrypt(s.password)); put("path", s.path); put("domain", s.domain)
            put("encoding", s.encoding); put("passive", s.passiveMode)
            put("privateKey", Crypto.encrypt(s.privateKey)); put("anonymous", s.anonymous)
            put("jumpHost", s.jumpHost); put("jumpPort", s.jumpPort); put("jumpUser", s.jumpUser)
            put("jumpPassword", Crypto.encrypt(s.jumpPassword)); put("jumpPrivateKey", Crypto.encrypt(s.jumpPrivateKey))
        })
        p.edit().putString("list", arr.toString()).apply()
    }

    fun get(id: String) = servers.firstOrNull { it.id == id }

    fun upsert(s: ServerConfig) {
        val i = servers.indexOfFirst { it.id == s.id }
        if (i >= 0) servers[i] = s else servers.add(s)
        persist()
    }

    fun remove(id: String) { servers.removeAll { it.id == id }; persist() }
}

data class Bookmark(val label: String, val fsId: String, val path: String)

class Bookmarks(context: Context) {
    private val p = context.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)
    val items = mutableStateListOf<Bookmark>().apply {
        val arr = runCatching { JSONArray(p.getString("list", "[]")) }.getOrDefault(JSONArray())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            add(Bookmark(o.getString("label"), o.getString("fs"), o.getString("path")))
        }
    }

    private fun persist() {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().put("label", it.label).put("fs", it.fsId).put("path", it.path)) }
        p.edit().putString("list", arr.toString()).apply()
    }

    fun add(b: Bookmark) { if (items.none { it.fsId == b.fsId && it.path == b.path }) { items.add(b); persist() } }
    fun remove(b: Bookmark) { items.remove(b); persist() }
}

class HostKeyPrefs(context: Context) : HostKeyStore {
    private val p = context.getSharedPreferences("host_keys", Context.MODE_PRIVATE)
    override fun get(hostPort: String): String? = p.getString(hostPort, null)
    override fun put(hostPort: String, fingerprint: String) { p.edit().putString(hostPort, fingerprint).apply() }
    fun forget(hostPort: String) { p.edit().remove(hostPort).apply() }
}
