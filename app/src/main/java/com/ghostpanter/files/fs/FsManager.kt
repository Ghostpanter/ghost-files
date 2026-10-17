package com.ghostpanter.files.fs

import android.content.Context
import android.net.Uri
import android.os.Environment
import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.LocalFs
import com.ghostpanter.files.core.RemoteFsFactory
import com.ghostpanter.files.core.ShellFs
import com.ghostpanter.files.core.VirtualFs
import com.ghostpanter.files.data.HostKeyPrefs
import com.ghostpanter.files.data.ServerStore
import com.ghostpanter.files.t
import java.util.concurrent.ConcurrentHashMap

/** Resolves backend ids ("local", "root", "shizuku", "net:<id>", "saf:<uri>") to live file systems. */
class FsManager(
    private val context: Context,
    private val servers: ServerStore,
    private val hostKeys: HostKeyPrefs,
) {
    val storageRoot: String = Environment.getExternalStorageDirectory().path

    val local = LocalFs(LocalFs.ID, t("本机", "Local"), storageRoot)
    val root: ShellFs by lazy { ShellFs(ROOT, "Root", RootRunner, "/") }
    val shizuku: ShellFs by lazy { ShellFs(SHIZUKU, "Shizuku", ShizukuRunner, "$storageRoot/Android/data") }

    private val debugSh: ShellFs by lazy {
        ShellFs(DEBUG_SH, "DebugSh", { script -> ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start() }, "/")
    }

    private val remotes = ConcurrentHashMap<String, VirtualFs>()
    private val safs = ConcurrentHashMap<String, SafFs>()

    fun get(id: String): VirtualFs = when {
        id == LocalFs.ID -> local
        id == ROOT -> root
        id == SHIZUKU -> shizuku
        // Debug builds only: ShellFs over the app's own `sh` with merged stderr (same shape as RootRunner),
        // so the Root/Shizuku code paths can be exercised on an emulator without su.
        id == DEBUG_SH && com.ghostpanter.files.BuildConfig.DEBUG -> debugSh
        id.startsWith("net:") -> remotes.getOrPut(id) {
            val cfg = servers.get(id.removePrefix("net:")) ?: throw FsException(t("服务器配置已删除", "Server was removed"))
            RemoteFsFactory.create(cfg, hostKeys)
        }
        id.startsWith("saf:") -> safs.getOrPut(id) { SafFs(context, Uri.parse(id.removePrefix("saf:")), id) }
        else -> throw FsException("unknown fs $id")
    }

    /**
     * A private connection for one transfer job (not shared with browsing or other jobs), or
     * null for non-network backends. The caller closes it.
     */
    fun openDedicated(id: String): VirtualFs? {
        if (!id.startsWith("net:")) return null
        val cfg = servers.get(id.removePrefix("net:")) ?: return null
        return RemoteFsFactory.create(cfg, hostKeys, dedicated = true)
    }

    fun label(id: String): String = when {
        id == LocalFs.ID -> t("本机", "Local")
        id == ROOT -> "Root"
        id == SHIZUKU -> "Shizuku"
        id.startsWith("net:") -> servers.get(id.removePrefix("net:"))?.let { "${it.name} (${it.protocol.label})" } ?: id
        id.startsWith("saf:") -> "SAF: " + (Uri.parse(id.removePrefix("saf:")).lastPathSegment?.substringAfter(':') ?: "?")
        else -> id
    }

    /** Drop a cached connection (after editing/removing a server). */
    fun invalidate(id: String) {
        remotes.remove(id)?.let { fs -> Thread { runCatching { fs.close() } }.start() }
    }

    /** Local java.io path for an entry when it is directly accessible by the app. */
    fun localFile(fsId: String, path: String): java.io.File? =
        if (fsId == LocalFs.ID) java.io.File(path) else null

    /**
     * Paths that [LocalFs] (java.io.File) typically cannot list on modern Android,
     * even with MANAGE_EXTERNAL_STORAGE — need Root [ShellFs] (or Shizuku for Android/data).
     * Examples: `/`, `/storage`, `/storage/emulated`, `/data`, `/system`.
     * Primary user storage (`/storage/emulated/0/...`) stays on LocalFs.
     */
    fun prefersRoot(path: String): Boolean {
        val n = com.ghostpanter.files.core.VPath.normalize(path)
        if (n == storageRoot || n.startsWith("$storageRoot/")) {
            return n.contains("/Android/data") || n.contains("/Android/obb")
        }
        // Parents of emulated storage, or any non-/storage tree.
        if (n == "/storage" || n == "/storage/emulated") return true
        if (n.startsWith("/storage/")) return false // other volumes: try LocalFs first
        return true // /, /data, /system, /proc, ...
    }

    /**
     * If the tab asked for LocalFs but Root is already granted and the path needs elevation,
     * switch to the Root backend id. Magisk grant alone does not change LocalFs behaviour.
     */
    fun elevateFsId(fsId: String, path: String): String {
        if (fsId == LocalFs.ID && RootRunner.granted() && prefersRoot(path)) return ROOT
        return fsId
    }

    companion object {
        const val ROOT = "root"
        const val SHIZUKU = "shizuku"
        const val DEBUG_SH = "debugsh"

        fun looksLikePermissionDenied(msg: String?): Boolean {
            if (msg.isNullOrBlank()) return false
            val m = msg.lowercase()
            return "无权限" in msg || "permission" in m || "denied" in m || "eacces" in m ||
                "无法进入" in msg || "access" in m
        }
    }
}
