package com.ghostpanter.files

import android.app.Application
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.conscrypt.Conscrypt
import java.security.Security

class GhostApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        try {
            com.ghostpanter.scrcpy.ZhuoyitongGuard.localBlocked =
                com.ghostpanter.scrcpy.ZhuoyitongGuard.checkLocal(base).detected()
        } catch (_: Throwable) {
            com.ghostpanter.scrcpy.ZhuoyitongGuard.localBlocked = false
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (com.ghostpanter.scrcpy.ZhuoyitongGuard.isLocalBlocked()) {
            // Skip optional init; MainActivity exits silently.
            return
        }
        installSecurityProviders()
        graph = AppGraph(this)
        installScrcpy()
        com.ghostpanter.files.core.Lang.apply(graph.settings.language)
        com.ghostpanter.files.data.Settings.applyNightMode(this, graph.settings.themeMode)
        Thread { com.ghostpanter.files.ui.Thumbnails.trimDisk() }.start()
    }

    /** Embedded scrcpy-android: crash log + Shizuku listener, and in-app language for its View activities. */
    private fun installScrcpy() {
        com.ghostpanter.scrcpy.ScrcpyHost.install(this)
        com.ghostpanter.adb.AdbPool.init(this)
        com.ghostpanter.scrcpy.ScrcpyHost.setLocaleHook { base -> com.ghostpanter.files.ui.LocaleCtx.wrap(base) }
        // Light / dark / OLED of the View-based screens follows the in-app setting on every API level.
        com.ghostpanter.scrcpy.ScrcpyHost.setThemeHook(object : com.ghostpanter.scrcpy.ScrcpyHost.ThemeHook {
            override fun themeMode(): String = graph.settings.themeMode
            override fun oledBlack(): Boolean = graph.settings.oledBlack
        })
        com.ghostpanter.scrcpy.ScrcpyHost.setDeviceActions(object : com.ghostpanter.scrcpy.ScrcpyHost.DeviceActions {
            private fun open(ctx: android.content.Context, extra: String, host: String, port: Int) {
                val serial = com.ghostpanter.adb.AdbPool.serialOf(com.ghostpanter.scrcpy.Devices.Device(host, port))
                ctx.startActivity(android.content.Intent(ctx, com.ghostpanter.files.ui.MainActivity::class.java)
                    .putExtra(extra, serial)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            override fun openTools(ctx: android.content.Context, host: String, port: Int, name: String?) = open(ctx, com.ghostpanter.files.ui.MainActivity.EXTRA_ADB_TOOLS, host, port)
            override fun browseFiles(ctx: android.content.Context, host: String, port: Int, name: String?) = open(ctx, com.ghostpanter.files.ui.MainActivity.EXTRA_ADB_BROWSE, host, port)
        })
    }

    private fun installSecurityProviders() {
        // Conscrypt first: modern TLS (1.3, ChaCha20) for FTPS / HTTPS WebDAV on older Android.
        runCatching { Security.insertProviderAt(Conscrypt.newProvider(), 1) }
        // Android ships a stripped "BC" provider; sshj needs the full one (X25519, Ed25519...).
        runCatching {
            Security.removeProvider("BC")
            Security.addProvider(BouncyCastleProvider())
        }
    }

    companion object {
        lateinit var instance: GhostApp
            private set
        val graph: AppGraph get() = instance.graph
    }
}
