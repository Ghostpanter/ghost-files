package com.ghostpanter.files

import android.os.Environment
import java.io.File

/**
 * User-visible public folders and backup filenames for Dual-Pane File Manager.
 * Legacy "GhostFiles" / ghost-files-backup paths remain readable as a fallback.
 */
object BrandPaths {
    const val PUBLIC_FOLDER = "DualPane"
    const val LEGACY_PUBLIC_FOLDER = "GhostFiles"
    const val CONFIG_BACKUP = "dualpane-backup.gfbackup"
    const val LEGACY_CONFIG_BACKUP = "ghost-files-backup.gfbackup"

    /** DualPane under a public media directory (creates it). */
    fun publicDir(kind: String, sub: String = ""): File {
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(kind)
        val neu = File(base, if (sub.isBlank()) PUBLIC_FOLDER else "$PUBLIC_FOLDER/$sub")
        neu.mkdirs()
        return neu
    }

    fun legacyPublicDir(kind: String, sub: String = ""): File {
        @Suppress("DEPRECATION")
        val base = Environment.getExternalStoragePublicDirectory(kind)
        return File(base, if (sub.isBlank()) LEGACY_PUBLIC_FOLDER else "$LEGACY_PUBLIC_FOLDER/$sub")
    }

    /** New backups always go to Download/DualPane/apks. */
    fun apkBackupDir(): File = publicDir(Environment.DIRECTORY_DOWNLOADS, "apks")

    fun legacyApkBackupDir(): File = legacyPublicDir(Environment.DIRECTORY_DOWNLOADS, "apks")

    /** Resolve config backup: new name first, then legacy. */
    fun findConfigBackup(downloads: File?): File? {
        if (downloads == null) return null
        val neu = File(downloads, CONFIG_BACKUP)
        if (neu.isFile) return neu
        val old = File(downloads, LEGACY_CONFIG_BACKUP)
        return old.takeIf { it.isFile }
    }
}
