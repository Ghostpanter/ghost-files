package com.ghostpanter.adb.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** [UsbBulkIo] on a claimed ADB interface. Blocking transfers; closing the connection unblocks them. */
class AndroidUsbBulk(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : UsbBulkIo {
    override val maxPacketSize: Int get() = epOut.maxPacketSize
    override fun bulkIn(b: ByteArray, off: Int, len: Int): Int = conn.bulkTransfer(epIn, b, off, len, 0)
    override fun bulkOut(b: ByteArray, off: Int, len: Int): Int = conn.bulkTransfer(epOut, b, off, len, WRITE_TIMEOUT_MS)
    override fun close() {
        runCatching { conn.releaseInterface(intf) }
        runCatching { conn.close() }
    }

    companion object { const val WRITE_TIMEOUT_MS = 10_000 }
}
