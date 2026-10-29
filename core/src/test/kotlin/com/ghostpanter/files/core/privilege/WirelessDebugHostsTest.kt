package com.ghostpanter.files.core.privilege

import com.ghostpanter.files.core.privilege.WirelessDebugHosts.Ep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessDebugHostsTest {
    @Test fun loopbackFirstThenLan() {
        assertEquals(listOf("127.0.0.1", "192.168.1.5"), WirelessDebugHosts.candidates("192.168.1.5"))
        assertEquals(listOf("127.0.0.1"), WirelessDebugHosts.candidates(null))
        assertEquals(listOf("127.0.0.1"), WirelessDebugHosts.candidates("127.0.0.1"))
        assertEquals(listOf("127.0.0.1", "fe80::1"), WirelessDebugHosts.candidates("fe80::1%wlan0"))
    }

    @Test fun loopbackDetection() {
        assertTrue(WirelessDebugHosts.isLoopback("127.0.0.1"))
        assertTrue(WirelessDebugHosts.isLoopback("::1"))
        assertTrue(WirelessDebugHosts.isLoopback("[::1]"))
        assertTrue(WirelessDebugHosts.isLoopback("localhost"))
        assertFalse(WirelessDebugHosts.isLoopback("192.168.1.5"))
        assertFalse(WirelessDebugHosts.isLoopback("10.0.0.127"))
        assertFalse(WirelessDebugHosts.isLoopback(null))
    }

    @Test fun ownEndpointsFiltersOtherPhones() {
        val found = listOf(Ep("192.168.1.9", 40001), Ep("192.168.1.5", 37123))
        assertEquals(listOf(Ep("192.168.1.5", 37123)),
            WirelessDebugHosts.ownEndpoints(found, listOf("192.168.1.5", "fe80::aa%wlan0", "10.8.0.2")))
        // No local match (addresses unknown) → keep all.
        assertEquals(found, WirelessDebugHosts.ownEndpoints(found, emptyList()))
    }

    @Test fun vpnHintOnlyWithVpn() {
        assertEquals("x", WirelessDebugHosts.failure("x", false))
        val m = WirelessDebugHosts.failure("x", true)
        assertTrue(m.contains("VPN")); assertTrue(m.contains("127.0.0.1")); assertTrue(m.contains("Root"))
        assertTrue(m.contains("电脑 adb"))
    }

    @Test fun noVpnKeepsNormalPath() {
        // Pairing: discovered LAN address as before; connect: 127.0.0.1 as before. No binding.
        assertEquals(listOf("192.168.1.5"), WirelessDebugHosts.plan("192.168.1.5", "192.168.1.5", vpnActive = false))
        assertEquals(listOf("127.0.0.1"), WirelessDebugHosts.plan("127.0.0.1", "192.168.1.5", vpnActive = false))
        assertFalse(WirelessDebugHosts.bindToWifi("192.168.1.5", vpnActive = false))
    }

    @Test fun vpnUsesBypass() {
        assertEquals(listOf("127.0.0.1", "192.168.1.5"), WirelessDebugHosts.plan("192.168.1.5", "192.168.1.5", vpnActive = true))
        assertEquals(listOf("127.0.0.1", "192.168.1.5"), WirelessDebugHosts.plan("127.0.0.1", "192.168.1.5", vpnActive = true))
        assertEquals(listOf("127.0.0.1"), WirelessDebugHosts.plan("127.0.0.1", null, vpnActive = true))
        assertTrue(WirelessDebugHosts.bindToWifi("192.168.1.5", vpnActive = true))
        assertFalse(WirelessDebugHosts.bindToWifi("127.0.0.1", vpnActive = true))
    }
}
