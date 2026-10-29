package com.ghostpanter.adb

import android.content.Context
import com.ghostpanter.scrcpy.Adb
import com.ghostpanter.scrcpy.Devices
import com.ghostpanter.adb.usb.UsbAdb
import io.github.muntashirakon.adb.SocketHook
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Connection pool for target devices, keyed by serial ("host:port", IPv6 bracketed).
 * Reuses the mirror's live connection when it is on the same endpoint; otherwise keeps one
 * extra connection per device with the same ADB identity (pair once, use everywhere).
 */
object AdbPool {
    private val owned = ConcurrentHashMap<String, AdbTransport>()
    private val devices = ConcurrentHashMap<String, AdbDevice>()
    @Volatile private var appCtx: Context? = null
    /** Test hook: replaces the real connection factory. */
    @Volatile var factory: ((serial: String) -> AdbTransport)? = null
    /**
     * Optional per-host socket configurer for new TCP connections (e.g. bind this device's own Wi‑Fi
     * address to the Wi‑Fi network so a VPN cannot capture it). Null → default routing.
     */
    @Volatile var socketBinder: ((host: String) -> SocketHook.Configurer?)? = null

    fun init(ctx: Context) { appCtx = ctx.applicationContext; runCatching { UsbAdb.init(ctx) } }

    fun serialOf(d: Devices.Device): String = d.toString()

    fun parse(serial: String): Devices.Device =
        Devices.parseAddress(serial) ?: throw IOException("bad ADB serial: $serial")

    /** Saved (paired / directly connected) devices of the embedded scrcpy. */
    fun saved(): List<Devices.Device> = appCtx?.let { runCatching { Devices.load(it) }.getOrDefault(emptyList()) } ?: emptyList()

    fun device(serial: String): AdbDevice = devices.getOrPut(serial) { AdbDevice(serial) { transport(serial) } }

    /** A usable transport (blocking connect when needed). Call off the main thread. */
    @Synchronized
    fun transport(serial: String): AdbTransport {
        factory?.let { f -> return owned[serial]?.takeIf { it.alive } ?: f(serial).also { owned[serial] = it } }
        val ctx = appCtx ?: throw IOException("AdbPool not initialised")
        // USB: one shared connection per device (the interface can only be claimed once).
        if (UsbAdb.isUsbSerial(serial)) return LibAdbTransport(UsbAdb.sharedConnection(parse(serial).host, null), owned = false)
        val d = parse(serial)
        val adb = Adb.getInstance(ctx)
        adb.liveConnectionFor(d.host, d.port)?.let { return LibAdbTransport(it, owned = false) }
        owned[serial]?.let { if (it.alive) return it; runCatching { it.close() }; owned.remove(serial) }
        val cfg = runCatching { socketBinder?.invoke(d.host) }.getOrNull()
        if (cfg != null) SocketHook.set(cfg)
        val conn = try { adb.newConnection(d.host, d.port, null) } finally { if (cfg != null) SocketHook.clear() }
        return LibAdbTransport(conn, owned = true).also { owned[serial] = it }
    }

    /** Drop a broken connection so the next call reconnects. */
    fun invalidate(serial: String) {
        owned.remove(serial)?.let { t -> Thread { runCatching { t.close() } }.start() }
    }

    /**
     * Forget a saved (wireless) device: drop it from the embedded scrcpy's saved list, close our
     * pooled connection and clear its cached name / root flag. Only removes this app's record;
     * the pairing on the target device is untouched. Call off the main thread.
     */
    fun forget(serial: String) {
        val ctx = appCtx
        Devices.parseAddress(serial)?.let { d -> if (ctx != null) runCatching { Devices.remove(ctx, d) } }
        invalidate(serial)
        devices.remove(serial)
        names.remove(serial)
        prefs()?.edit()?.remove("name:$serial")?.remove("root:$serial")?.apply()
    }

    fun isConnected(serial: String): Boolean {
        if (UsbAdb.isUsbSerial(serial)) return UsbAdb.isLive(serial)
        if (owned[serial]?.alive == true) return true
        val ctx = appCtx ?: return false
        val d = Devices.parseAddress(serial) ?: return false
        return runCatching { Adb.getInstance(ctx).liveConnectionFor(d.host, d.port) != null }.getOrDefault(false)
    }

    fun closeAll() {
        owned.values.forEach { runCatching { it.close() } }
        owned.clear()
    }

    // ---- friendly names (model) cache ----
    private fun prefs() = appCtx?.getSharedPreferences("adb_devices", Context.MODE_PRIVATE)
    private val names = ConcurrentHashMap<String, String>()
    fun name(serial: String): String? = names[serial] ?: prefs()?.getString("name:$serial", null)?.also { names[serial] = it }
    fun setName(serial: String, name: String) {
        if (names[serial] == name) return
        names[serial] = name
        prefs()?.edit()?.putString("name:$serial", name)?.apply()
    }
    fun rootEnabled(serial: String): Boolean = prefs()?.getBoolean("root:$serial", false) ?: false
    fun setRootEnabled(serial: String, on: Boolean) { prefs()?.edit()?.putBoolean("root:$serial", on)?.apply() }
}
