package com.ghostpanter.adb.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import com.ghostpanter.scrcpy.Adb
import com.ghostpanter.scrcpy.Log
import io.github.muntashirakon.adb.AdbConnection
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ADB over USB host / OTG. A USB target is addressed like a network one so every existing path
 * (AdbPool, AdbFs, tools, logcat, terminal, the scrcpy mirror) works unchanged:
 * host = "usb-<key>", port = [PORT], serial = "usb-<key>:1".
 *
 * Only one process can claim the ADB interface, so all users share ONE connection per device
 * (the mirror borrows it too); it closes on detach.
 */
object UsbAdb {
    const val HOST_PREFIX = "usb-"
    const val PORT = 1
    const val ADB_CLASS = 0xFF
    const val ADB_SUBCLASS = 0x42
    const val ADB_PROTOCOL = 0x01
    const val ACTION_PERMISSION = "com.ghostpanter.adb.USB_PERMISSION"

    data class Target(
        val serial: String,
        val label: String,
        val hasPermission: Boolean,
        val device: UsbDevice?,
    )

    @Volatile private var appCtx: Context? = null
    private val shared = ConcurrentHashMap<String, AdbConnection>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @JvmStatic fun isUsbHost(host: String?): Boolean = host != null && host.startsWith(HOST_PREFIX)
    @JvmStatic fun isUsbSerial(serial: String?): Boolean = serial != null && serial.startsWith(HOST_PREFIX)
    fun keyOfSerial(serial: String): String = serial.removePrefix(HOST_PREFIX).substringBeforeLast(':')
    fun serialOfKey(key: String) = "$HOST_PREFIX$key:$PORT"

    /** Stable key: the USB serial number (needs permission on API 29+), else the bus path. */
    fun keyOf(d: UsbDevice, mgr: UsbManager): String {
        val sn = if (mgr.hasPermission(d)) runCatching { d.serialNumber }.getOrNull() else null
        return sanitize(sn?.takeIf { it.isNotBlank() } ?: ("bus" + d.deviceName.removePrefix("/dev/bus/usb/")))
    }

    fun sanitize(s: String): String = s.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "device" }.take(64)

    /** The ADB interface (class 0xFF / subclass 0x42 / protocol 0x01 with two bulk endpoints). */
    fun findAdbInterface(d: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            if (!isAdbInterface(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol)) continue
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
            }
            if (epIn != null && epOut != null) return Triple(intf, epIn, epOut)
        }
        return null
    }

    @JvmStatic fun isAdbInterface(cls: Int, sub: Int, proto: Int) = cls == ADB_CLASS && sub == ADB_SUBCLASS && proto == ADB_PROTOCOL

    fun init(ctx: Context) {
        if (appCtx != null) return
        val app = ctx.applicationContext
        appCtx = app
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                    val d = deviceExtra(i)
                    if (d != null) dropDetached(c)
                }
                listeners.forEach { runCatching { it() } }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(rx, f)
    }

    private fun deviceExtra(i: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        else @Suppress("DEPRECATION") i.getParcelableExtra(UsbManager.EXTRA_DEVICE)

    /** Close shared connections whose device is gone. */
    private fun dropDetached(ctx: Context) {
        val live = targets(ctx).map { keyOfSerial(it.serial) }.toSet()
        shared.keys.filter { it !in live }.forEach { k -> shared.remove(k)?.let { c -> Thread { runCatching { c.close() } }.start() } }
    }

    /** Called on attach / detach / permission results (any thread). */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun mgr(ctx: Context) = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    /** Attached devices that expose an ADB interface. */
    fun targets(ctx: Context): List<Target> {
        val m = mgr(ctx)
        return runCatching { m.deviceList.values.toList() }.getOrDefault(emptyList())
            .filter { findAdbInterface(it) != null }
            .map { d ->
                val perm = m.hasPermission(d)
                val name = if (perm) runCatching { listOfNotNull(d.manufacturerName, d.productName).joinToString(" ") }.getOrNull() else null
                Target(serialOfKey(keyOf(d, m)), name?.takeIf { it.isNotBlank() } ?: "USB %04x:%04x".format(d.vendorId, d.productId), perm, d)
            }
    }

    fun find(ctx: Context, key: String): UsbDevice? {
        val m = mgr(ctx)
        return runCatching { m.deviceList.values.toList() }.getOrDefault(emptyList())
            .firstOrNull { findAdbInterface(it) != null && keyOf(it, m) == key }
    }

    /** Shows the system "Allow access to the USB device?" dialog; [listeners] fire on the answer. */
    fun requestPermission(ctx: Context, d: UsbDevice) {
        val m = mgr(ctx)
        if (m.hasPermission(d)) { listeners.forEach { runCatching { it() } }; return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
        m.requestPermission(d, pi)
    }

    fun isLive(serial: String): Boolean = shared[keyOfSerial(serial)]?.isConnectionEstablished == true

    /**
     * The shared connection for "usb-<key>" (connects, and waits for "Allow USB debugging?" on the
     * target when the key is new). Blocking; call off the main thread.
     */
    @JvmStatic
    @Throws(Exception::class)
    fun sharedConnection(host: String, onPrompt: Adb.AuthPromptListener?): AdbConnection {
        val key = host.removePrefix(HOST_PREFIX)
        shared[key]?.let { if (it.isConnectionEstablished) return it }
        synchronized(lockFor(key)) {
            shared[key]?.let { c -> if (c.isConnectionEstablished) return c; shared.remove(key); runCatching { c.close() } }
            val ctx = appCtx ?: throw IOException("USB ADB not initialised")
            val m = mgr(ctx)
            val d = find(ctx, key) ?: throw IOException("USB device not attached")
            if (!m.hasPermission(d)) throw IOException("USB permission not granted")
            val (intf, epIn, epOut) = findAdbInterface(d) ?: throw IOException("no ADB interface on USB device")
            val conn = m.openDevice(d) ?: throw IOException("cannot open USB device")
            if (!conn.claimInterface(intf, true)) { conn.close(); throw IOException("cannot claim the ADB USB interface (is another app using it?)") }
            val link = UsbAdbLink(AndroidUsbBulk(conn, intf, epIn, epOut))
            Log.i("usb-adb: opened %s (mps=%d)", key, epOut.maxPacketSize)
            val c = Adb.getInstance(ctx).establishLink(link, host, onPrompt)
            shared[key] = c
            return c
        }
    }

    private val locks = ConcurrentHashMap<String, Any>()
    private fun lockFor(key: String) = locks.getOrPut(key) { Any() }

    fun close(serial: String) { shared.remove(keyOfSerial(serial))?.let { runCatching { it.close() } } }
}
