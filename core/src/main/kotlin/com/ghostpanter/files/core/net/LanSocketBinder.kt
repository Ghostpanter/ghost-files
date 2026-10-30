package com.ghostpanter.files.core.net

import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Pluggable per-socket binder for LAN clients (SMB / FTP / SFTP / WebDAV / port sweep).
 *
 * The app installs [resolver] once at startup. When a VPN is active it returns a configurer that
 * calls `Network.bindSocket` on the Wi‑Fi network; otherwise null — routing stays untouched.
 * Destination host is tracked in a ThreadLocal around connect/open so every socket on that thread
 * (including smbj's no-arg [SocketFactory.createSocket] + later connect) gets the right decision,
 * then cleared — other threads / non-LAN traffic are never forced off the VPN.
 */
object LanSocketBinder {
    fun interface Configurer {
        fun configure(socket: Socket)
    }

    /** `(destinationHost) -> configurer or null`. Host may be null when not yet known. */
    @Volatile var resolver: ((host: String?) -> Configurer?)? = null

    @PublishedApi
    internal val destination = ThreadLocal<String?>()

    fun currentHost(): String? = destination.get()

    /** Runs [block] with [host] as the destination for [apply] on this thread; always restores. Inline so `return` in callers works. */
    inline fun <T> withDestination(host: String?, block: () -> T): T {
        val prev = destination.get()
        destination.set(host)
        return try {
            block()
        } finally {
            if (prev != null) destination.set(prev) else destination.remove()
        }
    }

    /** Binds [socket] to Wi‑Fi when [resolver] says so for [host] (defaults to the thread destination). */
    fun apply(socket: Socket, host: String? = destination.get()) {
        val cfg = runCatching { resolver?.invoke(host) }.getOrNull() ?: return
        runCatching { cfg.configure(socket) }
    }

    /**
     * Socket factory used by SMB (and optionally FTP/SFTP). Applies the binder before connect;
     * keepalive + TCP_NODELAY; bounded connect timeout on the connect overloads.
     */
    class Factory(private val connectMs: Int) : SocketFactory() {
        private fun prep(s: Socket) = s.apply {
            keepAlive = true
            tcpNoDelay = true
            runCatching { receiveBufferSize = 1 shl 20 }
        }

        override fun createSocket(): Socket = prep(Socket()).also { apply(it) }

        override fun createSocket(host: String, port: Int): Socket =
            prep(Socket()).also {
                apply(it, host)
                it.connect(InetSocketAddress(host, port), connectMs)
            }

        override fun createSocket(host: String, port: Int, localHost: java.net.InetAddress?, localPort: Int): Socket =
            prep(Socket()).also {
                apply(it, host)
                it.bind(InetSocketAddress(localHost, localPort))
                it.connect(InetSocketAddress(host, port), connectMs)
            }

        override fun createSocket(host: java.net.InetAddress, port: Int): Socket =
            prep(Socket()).also {
                val name = host.hostAddress ?: host.hostName
                apply(it, name)
                it.connect(InetSocketAddress(host, port), connectMs)
            }

        override fun createSocket(
            address: java.net.InetAddress,
            port: Int,
            localAddress: java.net.InetAddress?,
            localPort: Int,
        ): Socket = prep(Socket()).also {
            val name = address.hostAddress ?: address.hostName
            apply(it, name)
            it.bind(InetSocketAddress(localAddress, localPort))
            it.connect(InetSocketAddress(address, port), connectMs)
        }
    }
}
