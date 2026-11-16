package com.ghostpanter.adb.usb

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface

/** [UsbBulkIo] on a claimed USB interface. Blocking transfers; closing the connection unblocks them. */
class AndroidUsbBulk(
    private val conn: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val readTimeoutMs: Int = 0,
    private val writeTimeoutMs: Int = WRITE_TIMEOUT_MS,
) : UsbBulkIo {
    override val maxPacketSize: Int get() = epOut.maxPacketSize
    override fun bulkIn(b: ByteArray, off: Int, len: Int): Int = conn.bulkTransfer(epIn, b, off, len, readTimeoutMs)
    override fun bulkOut(b: ByteArray, off: Int, len: Int): Int = conn.bulkTransfer(epOut, b, off, len, writeTimeoutMs)
    override fun close() {
        runCatching { conn.releaseInterface(intf) }
        runCatching { conn.close() }
    }

    companion object {
        const val WRITE_TIMEOUT_MS = 10_000
        /** Fastboot flash / erase / getvar-all can sit on IN for minutes. */
        const val FASTBOOT_READ_TIMEOUT_MS = 300_000
        const val FASTBOOT_WRITE_TIMEOUT_MS = 60_000
    }
}
