package com.ghostpanter.files.data

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.ghostpanter.files.core.server.HostedKind
import com.ghostpanter.files.core.server.HostedServiceConfig
import com.ghostpanter.files.core.server.randomPassword
import org.json.JSONObject
import java.io.File

/** Persists hosted-service settings; passwords go through [Crypto] (Android Keystore). */
class HostedStore(context: Context) {
    private val p = context.getSharedPreferences("hosted_services", Context.MODE_PRIVATE)
    private val defaultRoot: String = run {
        val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (dl != null) {
            dl.mkdirs()
            dl.absolutePath
        } else File(context.getExternalFilesDir(null), "share").apply { mkdirs() }.absolutePath
    }

    val configs: SnapshotStateMap<HostedKind, HostedServiceConfig> = mutableStateMapOf()

    init {
        for (k in HostedKind.entries) {
            configs[k] = load(k) ?: HostedServiceConfig(
                kind = k,
                port = k.defaultPort,
                username = "ghost",
                password = randomPassword(),
                rootPath = defaultRoot,
                useTls = k == HostedKind.FTPS || k == HostedKind.WEBDAVS,
            ).also { persist(it) }
        }
    }

    fun defaultRoot(): String = defaultRoot

    fun get(kind: HostedKind): HostedServiceConfig = configs[kind]!!

    fun upsert(c: HostedServiceConfig) {
        configs[c.kind] = c
        persist(c)
    }

    private fun load(kind: HostedKind): HostedServiceConfig? {
        val raw = p.getString(kind.name, null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            HostedServiceConfig(
                kind = kind,
                enabled = o.optBoolean("enabled", false),
                port = o.optInt("port", kind.defaultPort),
                username = o.optString("username", "ghost"),
                password = Crypto.decrypt(o.optString("password")),
                anonymous = o.optBoolean("anonymous", false),
                rootPath = o.optString("rootPath", defaultRoot),
                pasvPortStart = o.optInt("pasvStart", 2122),
                pasvPortEnd = o.optInt("pasvEnd", 2130),
                useTls = o.optBoolean("useTls", kind == HostedKind.FTPS || kind == HostedKind.WEBDAVS),
                lanOnly = o.optBoolean("lanOnly", true),
                stopOnWifiLost = o.optBoolean("stopOnWifiLost", true),
            )
        }.getOrNull()
    }

    private fun persist(c: HostedServiceConfig) {
        val o = JSONObject()
            .put("enabled", c.enabled)
            .put("port", c.port)
            .put("username", c.username)
            .put("password", Crypto.encrypt(c.password))
            .put("anonymous", c.anonymous)
            .put("rootPath", c.rootPath)
            .put("pasvStart", c.pasvPortStart)
            .put("pasvEnd", c.pasvPortEnd)
            .put("useTls", c.useTls)
            .put("lanOnly", c.lanOnly)
            .put("stopOnWifiLost", c.stopOnWifiLost)
        p.edit().putString(c.kind.name, o.toString()).apply()
    }
}
