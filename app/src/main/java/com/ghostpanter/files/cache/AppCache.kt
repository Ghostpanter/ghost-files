package com.ghostpanter.files.cache

import android.content.Context
import android.os.Build
import android.webkit.WebView
import com.ghostpanter.files.core.cache.CacheJanitor
import com.ghostpanter.files.ui.Thumbnails
import com.ghostpanter.files.ui.image.GhostImageLoader
import java.io.File

/**
 * Owns the on-disk caches that Android Settings reports as "Cache" ([Context.getCacheDir]
 * and [Context.getExternalCacheDir]).
 *
 * Root cause of multi-GB growth: remote previews and the ExoPlayer audio path copy whole files
 * into `open/` and `play/` and never evict them. Archive / install / sideload temps at the
 * cache root and leftover `vol-*` / `thumb-tmp` trees also survive process death. Coil and
 * list thumbs are already capped; these ephemeral copies were not.
 */
object AppCache {
    private const val MB = 1024L * 1024L
    const val PLAY = 256L * MB
    const val OPEN = 256L * MB
    const val INCOMING = 256L * MB
    const val INSTALL = 256L * MB
    const val APKS = 128L * MB
    const val REMOTE_APPS = 32L * MB
    const val APP_ICONS = 32L * MB
    const val THUMBS = 64L * MB
    const val SPACE = 16L * MB
    const val ZIPEDIT = 64L * MB
    const val VOLZIP = 64L * MB
    /** Soft cap for the whole cacheDir; if exceeded, ephemeral dirs are trimmed harder. */
    const val TOTAL_SOFT = 768L * MB
    private const val DAY = 24L * 60L * 60L * 1000L

    fun bytes(ctx: Context): Long =
        CacheJanitor.sizeOf(ctx.cacheDir) + (ctx.externalCacheDir?.let { CacheJanitor.sizeOf(it) } ?: 0L)

    /**
     * Startup / after a large write / onTrimMemory: drop leftovers, then LRU-cap each bucket.
     * [aggressive] also wipes play/open/incoming (used when the process is in the background
     * or the user is low on memory).
     */
    fun trim(ctx: Context, aggressive: Boolean = false): Long {
        var freed = 0L
        val cache = ctx.cacheDir
        freed += dropLeftovers(cache)
        ctx.externalCacheDir?.let { freed += dropLeftovers(it) }
        if (aggressive) {
            freed += CacheJanitor.deleteTree(File(cache, "play"))
            freed += CacheJanitor.deleteTree(File(cache, "open"))
            freed += CacheJanitor.deleteTree(File(cache, "incoming"))
            freed += CacheJanitor.deleteTree(File(cache, "thumb-tmp"))
        } else {
            freed += CacheJanitor.deleteOlderThan(File(cache, "play"), DAY)
            freed += CacheJanitor.deleteOlderThan(File(cache, "open"), 2 * DAY)
            freed += CacheJanitor.deleteOlderThan(File(cache, "incoming"), 2 * DAY)
            freed += CacheJanitor.lruTrim(File(cache, "play"), PLAY)
            freed += CacheJanitor.lruTrim(File(cache, "open"), OPEN)
            freed += CacheJanitor.lruTrim(File(cache, "incoming"), INCOMING)
        }
        freed += CacheJanitor.lruTrim(File(cache, "install"), INSTALL)
        freed += CacheJanitor.lruTrim(File(cache, "apks"), APKS)
        freed += CacheJanitor.lruTrim(File(cache, "remote-apps"), REMOTE_APPS)
        freed += CacheJanitor.lruTrim(File(cache, "app-icons"), APP_ICONS)
        freed += CacheJanitor.lruTrim(File(cache, "space"), SPACE)
        freed += CacheJanitor.lruTrim(File(cache, "zipedit"), ZIPEDIT)
        freed += CacheJanitor.lruTrim(File(cache, "volzip"), VOLZIP)
        runCatching { Thumbnails.trimDisk(THUMBS) }
        if (bytes(ctx) > TOTAL_SOFT) {
            freed += CacheJanitor.lruTrim(File(cache, "play"), 64L * MB)
            freed += CacheJanitor.lruTrim(File(cache, "open"), 64L * MB)
            freed += CacheJanitor.lruTrim(File(cache, "incoming"), 64L * MB)
            freed += CacheJanitor.lruTrim(File(cache, "install"), 32L * MB)
        }
        return freed
    }

    /** User "clear cache": frees everything Settings counts as cache (same dirs). */
    fun clear(ctx: Context): Long {
        val before = bytes(ctx)
        val cache = ctx.cacheDir
        runCatching { GhostImageLoader.get(ctx).diskCache?.clear() }
        runCatching { Thumbnails.clearAll() }
        runCatching {
            val w = WebView(ctx)
            w.clearCache(true)
            if (Build.VERSION.SDK_INT >= 28) WebView.clearClientCertPreferences(null)
            w.destroy()
        }
        for (name in listOf(
            "play", "open", "incoming", "install", "apks", "remote-apps",
            "thumb-tmp", "thumbs", "coil_image_cache", "zipedit", "volzip",
            "space", "WebView", "webview", "org.chromium.android_webview",
            "image_manager_disk_cache",
        )) {
            CacheJanitor.deleteTree(File(cache, name))
            ctx.externalCacheDir?.let { CacheJanitor.deleteTree(File(it, name)) }
        }
        dropLeftovers(cache)
        ctx.externalCacheDir?.let { dropLeftovers(it) }
        // app-icons can be rebuilt; drop them too so the reported cache goes to ~0.
        CacheJanitor.deleteTree(File(cache, "app-icons"))
        return (before - bytes(ctx)).coerceAtLeast(0)
    }

    private fun dropLeftovers(dir: File): Long {
        if (!dir.isDirectory) return 0
        var freed = 0L
        freed += CacheJanitor.deleteTree(File(dir, "thumb-tmp"))
        dir.listFiles()?.forEach { f ->
            val n = f.name
            val drop = when {
                f.isDirectory && (n.startsWith("vol-") || n.startsWith("open/") ) -> true
                f.isDirectory && n.startsWith("vol-") -> true
                f.isFile && (
                    n.startsWith("arc") && n.endsWith(".tmp") ||
                    n.startsWith("gf-7z") ||
                    n.startsWith("enc") && n.endsWith(".zip") ||
                    n.startsWith("bundle-") ||
                    n.startsWith("sideload-") ||
                    n == "hex-save.bin"
                ) -> true
                else -> false
            }
            if (drop) freed += if (f.isDirectory) CacheJanitor.deleteTree(f) else { val sz = f.length(); if (f.delete()) sz else 0 }
        }
        return freed
    }
}
