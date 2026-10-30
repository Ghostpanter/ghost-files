package com.ghostpanter.files.privilege

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.ghostpanter.files.core.net.LanSocketBinder
import com.ghostpanter.files.core.net.LanVpnPolicy
import com.ghostpanter.files.core.privilege.WirelessDebugHosts
import io.github.muntashirakon.adb.SocketHook
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * Wi‑Fi vs VPN helpers shared by wireless debugging and LAN clients (SMB / FTP / SFTP / WebDAV).
 *
 * A full-tunnel VPN becomes the default network and captures traffic to LAN addresses (NAS
 * 192.168.x.x, the phone's own Wi‑Fi IP). Loopback is never tunnelled; a socket bound to the
 * underlying Wi‑Fi [Network] bypasses the VPN for that connection only.
 *
 * Detection is always at call time ([vpnActive]) — never hardcoded “always bypass”.
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

    /**
     * LAN-client binder (SMB etc.): VPN active + [LanVpnPolicy.shouldBindToWifi] for [host], else null.
     * Uses the same Wi‑Fi [Network.bindSocket] as wireless-debug.
     */
    fun lanBinderFor(ctx: Context, host: String?): LanSocketBinder.Configurer? {
        if (!LanVpnPolicy.shouldBindToWifi(host, vpnActive(ctx))) return null
        val n = wifiNetwork(ctx) ?: return null
        return LanSocketBinder.Configurer { s -> n.bindSocket(s) }
    }

    /** Installs [LanSocketBinder] / [LanVpnPolicy] hooks so core LAN clients can bypass a VPN. Call once from [com.ghostpanter.files.GhostApp]. */
    fun installLanHooks(ctx: Context) {
        val app = ctx.applicationContext
        LanVpnPolicy.vpnActive = { vpnActive(app) }
        LanSocketBinder.resolver = { host -> lanBinderFor(app, host) }
    }

    /** Runs [block] with this thread's ADB sockets bound to Wi‑Fi when a VPN is active (non-loopback [host]); always clears. */
    fun <T> withBinding(ctx: Context, host: String, block: () -> T): T {
        val cfg = binderFor(ctx, host)
        if (cfg != null) SocketHook.set(cfg)
        try { return block() } finally { if (cfg != null) SocketHook.clear() }
    }
}
