package com.ghostpanter.files.server

import android.content.Context
import android.net.wifi.WifiManager
import com.ghostpanter.files.core.server.isLanAddress
import com.ghostpanter.files.privilege.WifiBypass
import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddresses {
    /**
     * Best IPv4 for sharing on the current Wi‑Fi / hotspot — never the VPN tunnel address.
     * Prefer the underlying Wi‑Fi [Network] link address (works while a full-tunnel VPN is up).
     */
    @Suppress("DEPRECATION")
    fun primaryIpv4(ctx: Context): String? {
        WifiBypass.wifiIpv4(ctx)?.first?.let { return it }
        runCatching {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val ip = wm.connectionInfo?.ipAddress ?: 0
            if (ip != 0) {
                val dotted = String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff,
                )
                if (dotted != "0.0.0.0") return dotted
            }
        }
        // Prefer wlan/eth; fall back to any private IPv4 only if nothing else is found.
        return ipv4OnInterfaces(lanOnly = true).firstOrNull()
            ?: ipv4OnInterfaces(lanOnly = false).firstOrNull()
    }

    /** LAN-usable IPv4 addresses, VPN/mobile interfaces excluded when possible. */
    fun allIpv4(): List<String> =
        ipv4OnInterfaces(lanOnly = true).ifEmpty { ipv4OnInterfaces(lanOnly = false) }

    /** True when Wi‑Fi / Ethernet is up underneath, even if a VPN is the default network. */
    fun wifiConnected(ctx: Context): Boolean {
        if (WifiBypass.wifiNetwork(ctx) != null) return true
        return ipv4OnInterfaces(lanOnly = true).isNotEmpty()
    }

    private fun ipv4OnInterfaces(lanOnly: Boolean): List<String> = buildList {
        runCatching {
            val en = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                if (lanOnly && isLikelyVpnOrMobile(ni.name)) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is Inet4Address && isLanAddress(a.hostAddress ?: continue)) {
                        add(a.hostAddress!!)
                    }
                }
            }
        }
    }.distinct()

    private fun isLikelyVpnOrMobile(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") ||
            n.startsWith("ipsec") || n.startsWith("pptp") || n.contains("vpn") ||
            n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("v4-rmnet")
    }
}
