package com.ghostpanter.files.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import android.util.LruCache
import com.ghostpanter.adb.AdbPool
import com.ghostpanter.adb.RemoteApp
import com.ghostpanter.adb.RemoteApps
import com.ghostpanter.files.ui.adb.RemoteIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

/** Label + icon of the app a package-named folder belongs to. */
data class AppMeta(val label: String, val icon: Bitmap?)

/**
 * Icons for package folders (Android/data/<pkg>, /data/data/<pkg>…). This device's PackageManager for
 * local / SAF / Root / Shizuku, the target's package list + [RemoteIcons] for ADB. LRU in memory,
 * PNG + label on disk keyed by versionCode, loaded off the main thread. Missing packages are
 * remembered as such so the row keeps its folder icon without asking again.
 */
object AppIconCache {
    private val NONE = AppMeta("", null)
    private val mem = LruCache<String, AppMeta>(400)
    private val gate = Semaphore(3)
    private const val PX = 96

    /** null = not looked up yet; [AppMeta] with empty label = not installed. */
    fun peek(scope: String, pkg: String): AppMeta? = mem.get("$scope/$pkg")

    fun isMissing(m: AppMeta?) = m === NONE || (m != null && m.label.isEmpty() && m.icon == null)

    suspend fun load(ctx: Context, scope: String, pkg: String): AppMeta? {
        val k = "$scope/$pkg"
        mem.get(k)?.let { return it.takeUnless { isMissing(it) } }
        val m = gate.withPermit {
            mem.get(k) ?: withContext(Dispatchers.IO) {
                runCatching { if (scope == LOCAL) local(ctx, pkg) else remote(ctx, scope.removePrefix("adb:"), pkg) }.getOrNull() ?: NONE
            }.also { mem.put(k, it) }
        }
        return m.takeUnless { isMissing(it) }
    }

    const val LOCAL = "local"

    private fun disk(ctx: Context) = File(ctx.cacheDir, "app-icons").apply { mkdirs() }

    private fun local(ctx: Context, pkg: String): AppMeta? {
        val pm = ctx.packageManager
        val pi = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return null
        @Suppress("DEPRECATION")
        val vc = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else pi.versionCode.toLong()
        val base = File(disk(ctx), "${pkg}_$vc")
        val png = File("$base.png"); val txt = File("$base.txt")
        if (txt.isFile) return AppMeta(txt.readText().ifEmpty { pkg }, if (png.isFile) BitmapFactory.decodeFile(png.path) else null)
        val ai = pi.applicationInfo ?: return null
        val label = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
        val icon = runCatching {
            val d = ai.loadIcon(pm)
            Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, PX, PX); d.draw(Canvas(b)) }
        }.getOrNull()
        runCatching {
            txt.writeText(label)
            icon?.let { b -> png.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        }
        return AppMeta(label, icon)
    }

    // ---- ADB targets: one package listing per device (refreshed every few minutes) ----

    private class Listing(val at: Long, val apps: Map<String, RemoteApp>)
    private val listings = HashMap<String, Listing>()
    private val listLock = Mutex()

    private suspend fun listing(serial: String): Map<String, RemoteApp> = listLock.withLock {
        listings[serial]?.takeIf { System.currentTimeMillis() - it.at < 5 * 60_000 }?.let { return it.apps }
        val apps = withContext(Dispatchers.IO) {
            RemoteApps.parse(AdbPool.device(serial).sh(RemoteApps.LIST_SCRIPT, timeoutMs = 60_000).stdout)
        }.filter { !it.uninstalled }.associateBy { it.pkg }
        listings[serial] = Listing(System.currentTimeMillis(), apps)
        apps
    }

    private suspend fun remote(ctx: Context, serial: String, pkg: String): AppMeta? {
        val app = listing(serial)[pkg] ?: return null
        val m = RemoteIcons.load(ctx, serial, app)
        return AppMeta(m.label ?: pkg, m.icon)
    }
}
