package com.ghostpanter.files.core.ops

import com.ghostpanter.files.core.FsException
import com.ghostpanter.files.core.VPath
import java.io.File
import java.util.zip.ZipFile

/**
 * APKS / XAPK / APKM → extract contained APK splits into a folder.
 * Multi-split PackageInstaller session is handled in the app UI.
 */
object ApksOps {
    data class Result(val dir: File, val apks: List<File>, val label: String?)

    fun extract(archive: File, destDir: File): Result {
        if (!archive.isFile) throw FsException("文件不存在")
        destDir.mkdirs()
        val apks = ArrayList<File>()
        var label: String? = null
        ZipFile(archive).use { z ->
            val entries = z.entries().asSequence().toList()
            entries.firstOrNull { it.name.equals("manifest.json", true) }?.let { me ->
                z.getInputStream(me).bufferedReader().use { r ->
                    val text = r.readText()
                    val m = Regex(""""name"\s*:\s*"([^"]+)"""").find(text)
                    label = m?.groupValues?.getOrNull(1)
                }
            }
            for (e in entries) {
                if (e.isDirectory) continue
                val name = VPath.name(e.name)
                if (!name.endsWith(".apk", true)) continue
                val out = File(destDir, name)
                z.getInputStream(e).use { ins -> out.outputStream().use { ins.copyTo(it, 256 * 1024) } }
                apks += out
            }
        }
        if (apks.isEmpty()) throw FsException("压缩包内没有 APK")
        return Result(destDir, apks.sortedBy { it.name }, label)
    }

    fun isBundle(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith(".apks") || n.endsWith(".xapk") || n.endsWith(".apkm")
    }
}
