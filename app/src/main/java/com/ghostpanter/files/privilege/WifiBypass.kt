package com.ghostpanter.files.privilege

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ghostpanter.files.core.privilege.WirelessDebugHosts
import io.github.muntashirakon.adb.SocketHook
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Wi‑Fi vs VPN helpers for same-device wireless debugging. A full-tunnel VPN becomes the default network
 * and captures traffic to the phone's own 192.168.x.x; loopback is never tunnelled, and a socket bound to
 * the Wi‑Fi [Network] bypasses the VPN for the LAN fallback.
 */
object WifiBypass {
    private fun cm(ctx: Context) = ctx.getSystemService(ConnectivityManager::class.java)

    @Suppress("DEPRECATION")
    private fun all(ctx: Context): List<Pair<Network, NetworkCapabilities>> {
        val cm = cm(ctx) ?: return emptyList()
        return runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList()).mapNotNull { n ->
            runCatching { cm.getNetworkCapabilities(n) }.getOrNull()?.let { n to it }
        }
    }

    /** The underlying (non-VPN) Wi‑Fi / Ethernet network, even when a VPN is the default network. */
    fun wifiNetwork(ctx: Context): Network? = all(ctx).firstOrNull { (_, c) ->
        !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
    }?.first

    fun vpnActive(ctx: Context): Boolean = all(ctx).any { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }

    /** All addresses of this device (Wi‑Fi link addresses + every interface), without zone ids. */
    fun localAddresses(ctx: Context): Set<String> {
        val out = linkedSetOf<String>()
        wifiNetwork(ctx)?.let { n ->
            runCatching { cm(ctx)?.getLinkProperties(n)?.linkAddresses?.forEach { la -> la.address.hostAddress?.let { out += it.substringBefore('%') } } }
        }
        runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { ni ->
                ni.inetAddresses.toList().forEach { a ->
                    if (a is Inet4Address || a is Inet6Address) a.hostAddress?.let { out += it.substringBefore('%') }
                }
            }
        }
        return out
    }

    fun isOwnAddress(ctx: Context, host: String): Boolean =
        !WirelessDebugHosts.isLoopback(host) && host.substringBefore('%') in localAddresses(ctx)

    /**
     * Socket configurer binding to Wi‑Fi — only while a VPN is active (detected at call time), never for
     * loopback, and null when no Wi‑Fi network exists. Without a VPN routing stays untouched.
     */
    fun binderFor(ctx: Context, host: String): SocketHook.Configurer? {
        if (!WirelessDebugHosts.bindToWifi(host, vpnActive(ctx))) return null
        val n = wifiNetwork(ctx) ?: return null
        return SocketHook.Configurer { s -> n.bindSocket(s) }
    }

    /** Runs [block] with this thread's ADB sockets bound to Wi‑Fi when a VPN is active (non-loopback [host]); always clears. */
    fun <T> withBinding(ctx: Context, host: String, block: () -> T): T {
        val cfg = binderFor(ctx, host)
        if (cfg != null) SocketHook.set(cfg)
        try { return block() } finally { if (cfg != null) SocketHook.clear() }
    }
}
