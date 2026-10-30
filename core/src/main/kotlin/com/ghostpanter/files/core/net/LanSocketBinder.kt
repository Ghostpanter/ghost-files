package com.ghostpanter.files.core.net

import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Pluggable per-socket binder for LAN clients (SMB / FTP / SFTP / WebDAV / port sweep / NetBIOS).
 *
 * The app installs [resolver] / [socketFactoryFor] / [resolveOnNetwork] / [datagramBinder] once at
 * startup. When a VPN is active they route LAN destinations over the underlying Wi‑Fi network;
 * otherwise null — routing stays untouched.
 *
 * Destination host is tracked in a ThreadLocal around connect/open so every socket on that thread
 * (including smbj's no-arg [SocketFactory.createSocket] + later connect, and Commons Net FTP data
 * sockets) gets the right decision. [Factory] may also bake in a [Factory.defaultHost] so pools /
 * background threads that lose the ThreadLocal still bind correctly.
 */
object LanSocketBinder {
    fun interface Configurer {
        fun configure(socket: Socket)
    }

    /** `(destinationHost) -> configurer or null`. Host may be null when not yet known. */
    @Volatile var resolver: ((host: String?) -> Configurer?)? = null

    /**
     * Optional Android `Network.socketFactory` for [host]. Preferred over plain `Socket()` +
     * [Configurer] because some OEMs' `Network.bindSocket` is unreliable after a full-tunnel VPN
     * takes the default network.
     */
    @Volatile var socketFactoryFor: ((host: String?) -> SocketFactory?)? = null

    /** Resolve [host] on the Wi‑Fi network (so VPN DNS cannot hide LAN names). Null = platform DNS. */
    @Volatile var resolveOnNetwork: ((host: String) -> InetAddress?)? = null

    /** Bind a UDP socket to Wi‑Fi while a VPN is up (NetBIOS etc.). */
    @Volatile var datagramBinder: ((DatagramSocket) -> Unit)? = null

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

    fun effectiveHost(explicit: String? = null): String? = explicit ?: destination.get()

    /** Binds [socket] to Wi‑Fi when [resolver] says so for [host] (defaults to the thread destination). */
    fun apply(socket: Socket, host: String? = destination.get()) {
        val cfg = runCatching { resolver?.invoke(host) }.getOrNull() ?: return
        // Do not swallow: a failed bind leaves the socket on the VPN and LAN connects time out.
        cfg.configure(socket)
    }

    /** Applies [datagramBinder] when installed (no-op without a VPN / Wi‑Fi). */
    fun applyDatagram(socket: DatagramSocket) {
        runCatching { datagramBinder?.invoke(socket) }.getOrNull()
    }

    /**
     * Socket factory used by SMB / FTP / SFTP / WebDAV. Applies the binder before connect;
     * keepalive + TCP_NODELAY; bounded connect timeout on the connect overloads.
     *
     * @param defaultHost baked-in destination so sockets created without [withDestination]
     *   (FTP data connections, OkHttp pool threads, smbj reconnects) still bind under a VPN.
     */
    class Factory(
        private val connectMs: Int,
        private val defaultHost: String? = null,
    ) : SocketFactory() {
        private fun dest(explicit: String? = null): String? = explicit ?: destination.get() ?: defaultHost

        private fun prep(s: Socket) = s.apply {
            keepAlive = true
            tcpNoDelay = true
            runCatching { receiveBufferSize = 1 shl 20 }
        }

        /** Prefer the network's own socket factory; fall back to `Socket()` + [apply]. */
        private fun openSocket(host: String?): Socket {
            val netSf = runCatching { socketFactoryFor?.invoke(host) }.getOrNull()
            if (netSf != null) {
                return prep(netSf.createSocket()).also {
                    // Belt-and-suspenders: some Network.socketFactory impls still need bindSocket.
                    runCatching { apply(it, host) }
                }
            }
            return prep(Socket()).also { apply(it, host) }
        }

        private fun resolve(host: String): InetAddress {
            val viaNet = runCatching { resolveOnNetwork?.invoke(host) }.getOrNull()
            if (viaNet != null) return viaNet
            return InetAddress.getByName(host)
        }

        override fun createSocket(): Socket = openSocket(dest())

        override fun createSocket(host: String, port: Int): Socket {
            val h = dest(host) ?: host
            return openSocket(h).also {
                it.connect(InetSocketAddress(resolve(host), port), connectMs)
            }
        }

        override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket {
            val h = dest(host) ?: host
            return openSocket(h).also {
                if (localHost != null) it.bind(InetSocketAddress(localHost, localPort))
                it.connect(InetSocketAddress(resolve(host), port), connectMs)
            }
        }

        override fun createSocket(host: InetAddress, port: Int): Socket {
            val name = host.hostAddress ?: host.hostName
            val h = dest(name) ?: name
            return openSocket(h).also {
                it.connect(InetSocketAddress(host, port), connectMs)
            }
        }

        override fun createSocket(
            address: InetAddress,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int,
        ): Socket {
            val name = address.hostAddress ?: address.hostName
            val h = dest(name) ?: name
            return openSocket(h).also {
                if (localAddress != null) it.bind(InetSocketAddress(localAddress, localPort))
                it.connect(InetSocketAddress(address, port), connectMs)
            }
        }
    }
}
