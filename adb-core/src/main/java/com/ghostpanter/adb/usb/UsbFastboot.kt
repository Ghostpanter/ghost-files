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
import com.ghostpanter.adb.fastboot.FastbootClient
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Fastboot / fastbootd / bootloader over USB host / OTG.
 *
 * Primary match: vendor class 0xFF / subclass 0x42 / protocol 0x03 (AOSP gadget).
 * Fallback: well-known bootloader VID/PID pairs when the interface descriptors are incomplete.
 * Qualcomm EDL (0x9008) is never claimed.
 */
object UsbFastboot {
    const val HOST_PREFIX = "fb-"
    const val FB_CLASS = 0xFF
    const val FB_SUBCLASS = 0x42
    const val FB_PROTOCOL = 0x03
    const val ACTION_PERMISSION = "com.ghostpanter.adb.USB_PERMISSION"

    data class Target(
        val serial: String,
        val label: String,
        val hasPermission: Boolean,
        val device: UsbDevice?,
    )

    @Volatile private var appCtx: Context? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @JvmStatic fun isFastbootHost(host: String?): Boolean = host != null && host.startsWith(HOST_PREFIX)
    @JvmStatic fun isFastbootSerial(serial: String?): Boolean = serial != null && serial.startsWith(HOST_PREFIX)
    @JvmStatic fun supportsHost(ctx: Context): Boolean = UsbAdb.supportsHost(ctx)

    fun keyOfSerial(serial: String): String = serial.removePrefix(HOST_PREFIX)
    fun serialOfKey(key: String) = "$HOST_PREFIX$key"

    fun keyOf(d: UsbDevice, mgr: UsbManager): String {
        val sn = if (mgr.hasPermission(d)) runCatching { d.serialNumber }.getOrNull() else null
        return UsbAdb.sanitize(sn?.takeIf { it.isNotBlank() } ?: ("bus" + d.deviceName.removePrefix("/dev/bus/usb/")))
    }

    @JvmStatic
    fun isFastbootInterface(cls: Int, sub: Int, proto: Int) =
        cls == FB_CLASS && sub == FB_SUBCLASS && proto == FB_PROTOCOL

    /**
     * Well-known bootloader-only IDs used when class/subclass/protocol is missing.
     * Google 0x18d1:d00d / 4ee0 / 4ee2 are the common Pixel / AOSP ones; a few OEM PIDs are
     * included only when they uniquely identify fastboot (not ADB, not EDL).
     */
    @JvmStatic
    fun isKnownFastbootPid(vendorId: Int, productId: Int): Boolean {
        val vid = vendorId and 0xffff
        val pid = productId and 0xffff
        return when (vid) {
            0x18d1 -> pid in setOf(0xd00d, 0x4ee0, 0x4ee2, 0x0d02)
            0x0bb4 -> pid == 0x0fff // HTC
            0x22b8 -> pid == 0x2e80 // Motorola
            0x0fce -> pid == 0x0dde // Sony
            else -> false
        }
    }

    fun findFastbootInterface(d: UsbDevice): Triple<UsbInterface, UsbEndpoint, UsbEndpoint>? {
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            if (!isFastbootInterface(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol)) continue
            bulkPair(intf)?.let { return Triple(intf, it.first, it.second) }
        }
        if (!isKnownFastbootPid(d.vendorId, d.productId)) return null
        // Incomplete descriptors: first interface with two bulk endpoints, skip any ADB interface.
        for (i in 0 until d.interfaceCount) {
            val intf = d.getInterface(i)
            if (UsbAdb.isAdbInterface(intf.interfaceClass, intf.interfaceSubclass, intf.interfaceProtocol)) continue
            bulkPair(intf)?.let { return Triple(intf, it.first, it.second) }
        }
        return null
    }

    private fun bulkPair(intf: UsbInterface): Pair<UsbEndpoint, UsbEndpoint>? {
        var epIn: UsbEndpoint? = null
        var epOut: UsbEndpoint? = null
        for (e in 0 until intf.endpointCount) {
            val ep = intf.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
            if (ep.direction == UsbConstants.USB_DIR_IN) epIn = ep else epOut = ep
        }
        return if (epIn != null && epOut != null) epIn to epOut else null
    }

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
                listeners.forEach { runCatching { it() } }
            }
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") app.registerReceiver(rx, f)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    private fun mgr(ctx: Context) = ctx.getSystemService(Context.USB_SERVICE) as UsbManager

    fun targets(ctx: Context): List<Target> {
        val m = mgr(ctx)
        return runCatching { m.deviceList.values.toList() }.getOrDefault(emptyList())
            .filter { findFastbootInterface(it) != null }
            .map { d ->
                val perm = m.hasPermission(d)
                val name = if (perm) runCatching { listOfNotNull(d.manufacturerName, d.productName).joinToString(" ") }.getOrNull() else null
                Target(
                    serialOfKey(keyOf(d, m)),
                    name?.takeIf { it.isNotBlank() } ?: "Fastboot %04x:%04x".format(d.vendorId, d.productId),
                    perm,
                    d,
                )
            }
    }

    fun find(ctx: Context, key: String): UsbDevice? {
        val m = mgr(ctx)
        return runCatching { m.deviceList.values.toList() }.getOrDefault(emptyList())
            .firstOrNull { findFastbootInterface(it) != null && keyOf(it, m) == key }
    }

    fun requestPermission(ctx: Context, d: UsbDevice) {
        val m = mgr(ctx)
        if (m.hasPermission(d)) { listeners.forEach { runCatching { it() } }; return }
        val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ACTION_PERMISSION).setPackage(ctx.packageName), flags)
        m.requestPermission(d, pi)
    }

    /**
     * Open and claim the Fastboot interface. Caller must [FastbootClient.close].
     * Blocking; call off the main thread.
     */
    @Throws(Exception::class)
    fun open(ctx: Context, key: String): FastbootClient {
        val m = mgr(ctx)
        val d = find(ctx, key) ?: throw IOException("Fastboot device not attached")
        if (!m.hasPermission(d)) throw IOException("USB permission not granted")
        val (intf, epIn, epOut) = findFastbootInterface(d) ?: throw IOException("no Fastboot interface on USB device")
        val conn = m.openDevice(d) ?: throw IOException("cannot open USB device")
        if (!conn.claimInterface(intf, true)) {
            conn.close()
            throw IOException("cannot claim the Fastboot USB interface (is another app using it?)")
        }
        val bulk = AndroidUsbBulk(
            conn, intf, epIn, epOut,
            readTimeoutMs = AndroidUsbBulk.FASTBOOT_READ_TIMEOUT_MS,
            writeTimeoutMs = AndroidUsbBulk.FASTBOOT_WRITE_TIMEOUT_MS,
        )
        return FastbootClient(bulk)
    }
}
