package com.ghostpanter.adb

import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** One ADB stream (shell:, sync:, reboot:, ...). Closing it closes the remote service. */
interface AdbChannel : Closeable {
    val input: InputStream
    val output: OutputStream
}

/** A live ADB connection that can open service streams. Tests substitute a local fake. */
interface AdbTransport : Closeable {
    val alive: Boolean
    fun open(destination: String): AdbChannel
}

internal class LibAdbChannel(private val stream: AdbStream) : AdbChannel {
    override val input: InputStream = stream.openInputStream()
    override val output: OutputStream = stream.openOutputStream()
    override fun close() { runCatching { stream.close() } }
}

/**
 * [AdbConnection] adapter. [owned] = false for the mirror's own connection (borrowed: never
 * closed from here, so browsing files never tears down a running mirror).
 */
class LibAdbTransport(private val conn: AdbConnection, private val owned: Boolean) : AdbTransport {
    override val alive: Boolean get() = conn.isConnectionEstablished
    override fun open(destination: String): AdbChannel {
        if (!alive) throw IOException("ADB connection closed")
        return LibAdbChannel(conn.open(destination, OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }
    override fun close() { if (owned) runCatching { conn.close() } }

    companion object { const val OPEN_TIMEOUT_MS = 15_000L }
}
