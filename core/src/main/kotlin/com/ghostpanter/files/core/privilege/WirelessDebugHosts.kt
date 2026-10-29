package com.ghostpanter.files.core.privilege

/**
 * Host selection for same-device wireless debugging (内置提权).
 *
 * adbd's wireless-debugging pairing and TLS-connect servers listen on every interface, so the app can
 * always reach its own adbd through loopback. Loopback never enters a VPN tunnel, while the device's own
 * Wi‑Fi address (what mDNS reports, e.g. 192.168.x.x) is routed into a full-tunnel VPN and then fails or
 * hangs. So: loopback first, the LAN address (socket bound to the Wi‑Fi network) only as a fallback.
 */
object WirelessDebugHosts {
    const val LOOPBACK = "127.0.0.1"

    data class Ep(val host: String, val port: Int)

    fun isLoopback(host: String?): Boolean {
        val h = host?.trim()?.removePrefix("[")?.removeSuffix("]")?.substringBefore('%') ?: return false
        if (h.equals("localhost", ignoreCase = true)) return true
        if (h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        if (h.startsWith("::ffff:127.")) return true
        return h.startsWith("127.") && h.count { it == '.' } == 3
    }

    /** Ordered connect/pair host candidates: loopback first, then the discovered LAN host (if any, deduped). */
    fun candidates(discoveredHost: String?): List<String> {
        val out = mutableListOf(LOOPBACK)
        val d = discoveredHost?.trim()?.substringBefore('%')
        if (!d.isNullOrEmpty() && !isLoopback(d)) out += d
        return out
    }

    /**
     * Endpoints that belong to this device, given its interface addresses. mDNS on a shared LAN also
     * returns other phones' wireless-debugging services; those are useless (and wrong) for loopback.
     * When no endpoint matches (address list unavailable), all endpoints are returned unchanged.
     */
    fun ownEndpoints(found: List<Ep>, localAddrs: Collection<String>): List<Ep> {
        val local = localAddrs.map { norm(it) }.toSet()
        val mine = found.filter { norm(it.host) in local }
        return mine.ifEmpty { found }
    }

    private fun norm(h: String) = h.trim().removePrefix("[").removeSuffix("]").substringBefore('%').lowercase()

    /** Chinese-first failure text; adds VPN guidance when a VPN is up. */
    fun failure(base: String, vpnActive: Boolean): String =
        if (!vpnActive) base
        else "$base\n检测到 VPN：已先走 127.0.0.1 本机回环、再把局域网地址绑定到 Wi‑Fi 绕过 VPN，仍失败。" +
            "可临时关闭 VPN（或在 VPN 里放行本应用 / 局域网）后重试；也可用「电脑 adb 已连接」执行启动脚本，或用 Root 启动。"
}
