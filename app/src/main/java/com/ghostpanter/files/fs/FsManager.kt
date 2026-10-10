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

    private val remotes = ConcurrentHashMap<String, VirtualFs>()
    private val safs = ConcurrentHashMap<String, SafFs>()

    fun get(id: String): VirtualFs = when {
        id == LocalFs.ID -> local
        id == ROOT -> root
        id == SHIZUKU -> shizuku
        id.startsWith("net:") -> remotes.getOrPut(id) {
            val cfg = servers.get(id.removePrefix("net:")) ?: throw FsException(t("服务器配置已删除", "Server was removed"))
            RemoteFsFactory.create(cfg, hostKeys)
        }
        id.startsWith("saf:") -> safs.getOrPut(id) { SafFs(context, Uri.parse(id.removePrefix("saf:")), id) }
        else -> throw FsException("unknown fs $id")
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

    companion object {
        const val ROOT = "root"
        const val SHIZUKU = "shizuku"
    }
}
