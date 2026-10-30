package com.ghostpanter.files.core.net

import com.ghostpanter.files.core.privilege.WirelessDebugHosts
import com.ghostpanter.files.core.server.isLanAddress
import com.ghostpanter.files.core.tr

/**
 * When to bind LAN sockets onto the underlying Wi‑Fi network so a full-tunnel VPN cannot swallow them.
 *
 * Decided at call time: without a VPN → never bind (routing unchanged). With a VPN → bind only for
 * destinations that look like LAN (private / link-local IP, `.local`, short hostname), never for
 * loopback or public internet hosts that should stay on the tunnel.
 */
object LanVpnPolicy {
    /** App installs this; returns whether any network currently has TRANSPORT_VPN. */
    @Volatile var vpnActive: () -> Boolean = { false }

    fun vpnActiveNow(): Boolean = runCatching { vpnActive() }.getOrDefault(false)

    /**
     * Whether a socket to [host] should be bound to Wi‑Fi. [host] null means "destination unknown yet"
     * (e.g. bare [javax.net.SocketFactory.createSocket]); then we bind whenever a VPN is up so the
     * subsequent connect still skips the tunnel — callers that know the host should pass it.
     */
    fun shouldBindToWifi(host: String?, vpnActive: Boolean = vpnActiveNow()): Boolean {
        if (!vpnActive) return false
        if (host.isNullOrBlank()) return true
        val h = norm(host)
        if (WirelessDebugHosts.isLoopback(h)) return false
        if (isLanAddress(h)) return true
        if (h.endsWith(".local", ignoreCase = true)) return true
        // Single-label LAN hostname ("NAS", "router") — not a public FQDN.
        if ('.' !in h && ':' !in h && h.any { it.isLetter() }) return true
        return false
    }

    /** Chinese-first suffix for connect failures while a VPN is up; empty otherwise. */
    fun connectHint(vpnActive: Boolean = vpnActiveNow()): String =
        if (!vpnActive) ""
        else tr(
            "\n检测到 VPN：局域网流量可能被隧道拦截。已尝试绑定 Wi‑Fi 直连；若仍失败，请临时关闭 VPN，或在 VPN 中放行本应用 / 局域网（分流）。",
            "\nVPN detected: LAN traffic may be tunnelled. Tried binding sockets to Wi‑Fi; if it still fails, briefly disable the VPN or allow this app / LAN (split tunnel).",
        )

    private fun norm(host: String) =
        host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
}
