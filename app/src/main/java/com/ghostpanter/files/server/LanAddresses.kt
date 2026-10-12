package com.ghostpanter.files.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.ghostpanter.files.core.server.isLanAddress
import java.net.Inet4Address
import java.net.NetworkInterface

object LanAddresses {
    /** Best IPv4 for sharing on the current Wi-Fi / hotspot, or null. */
    @Suppress("DEPRECATION")
    fun primaryIpv4(ctx: Context): String? {
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
        return allIpv4().firstOrNull()
    }

    fun allIpv4(): List<String> = buildList {
        runCatching {
            val en = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
            while (en.hasMoreElements()) {
                val ni = en.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
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

    fun wifiConnected(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
