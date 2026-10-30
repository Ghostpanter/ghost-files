package com.ghostpanter.files.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

class LanVpnPolicyTest {
    @Test fun noVpnNeverBinds() {
        assertFalse(LanVpnPolicy.shouldBindToWifi("192.168.1.10", vpnActive = false))
        assertFalse(LanVpnPolicy.shouldBindToWifi(null, vpnActive = false))
        assertFalse(LanVpnPolicy.shouldBindToWifi("NAS.local", vpnActive = false))
        assertEquals("", LanVpnPolicy.connectHint(vpnActive = false))
    }

    @Test fun vpnBindsLanOnly() {
        assertTrue(LanVpnPolicy.shouldBindToWifi("192.168.1.10", vpnActive = true))
        assertTrue(LanVpnPolicy.shouldBindToWifi("10.0.0.2", vpnActive = true))
        assertTrue(LanVpnPolicy.shouldBindToWifi("172.16.5.1", vpnActive = true))
        assertTrue(LanVpnPolicy.shouldBindToWifi("NAS.local", vpnActive = true))
        assertTrue(LanVpnPolicy.shouldBindToWifi("NAS", vpnActive = true))
        assertTrue(LanVpnPolicy.shouldBindToWifi(null, vpnActive = true)) // unknown → bind
        assertFalse(LanVpnPolicy.shouldBindToWifi("127.0.0.1", vpnActive = true))
        assertFalse(LanVpnPolicy.shouldBindToWifi("8.8.8.8", vpnActive = true))
        assertFalse(LanVpnPolicy.shouldBindToWifi("files.example.com", vpnActive = true))
        val hint = LanVpnPolicy.connectHint(vpnActive = true)
        assertTrue(hint.contains("VPN"))
        assertTrue(hint.contains("局域网") || hint.contains("LAN"))
    }
}

class LanSocketBinderTest {
    @Test fun applyUsesResolverWithThreadHost() {
        val seen = AtomicReference<String?>(null)
        val prev = LanSocketBinder.resolver
        try {
            LanSocketBinder.resolver = { host ->
                seen.set(host)
                LanSocketBinder.Configurer { /* no-op */ }
            }
            LanSocketBinder.withDestination("192.168.1.50") {
                assertEquals("192.168.1.50", LanSocketBinder.currentHost())
                LanSocketBinder.apply(Socket())
            }
            assertEquals("192.168.1.50", seen.get())
            assertEquals(null, LanSocketBinder.currentHost())
        } finally {
            LanSocketBinder.resolver = prev
        }
    }

    @Test fun factoryInvokesResolverBeforeConnectOverload() {
        val seen = AtomicReference<String?>(null)
        val prev = LanSocketBinder.resolver
        val prevSf = LanSocketBinder.socketFactoryFor
        try {
            LanSocketBinder.socketFactoryFor = { null }
            LanSocketBinder.resolver = { host ->
                seen.set(host)
                null // do not actually bind/connect in unit test
            }
            // createSocket() only — no network I/O
            LanSocketBinder.withDestination("10.0.0.9") {
                LanSocketBinder.Factory(500).createSocket().close()
            }
            assertEquals("10.0.0.9", seen.get())
        } finally {
            LanSocketBinder.resolver = prev
            LanSocketBinder.socketFactoryFor = prevSf
        }
    }

    @Test fun factoryDefaultHostUsedWithoutThreadLocal() {
        val seen = AtomicReference<String?>(null)
        val prev = LanSocketBinder.resolver
        val prevSf = LanSocketBinder.socketFactoryFor
        try {
            LanSocketBinder.socketFactoryFor = { null }
            LanSocketBinder.resolver = { host ->
                seen.set(host)
                null
            }
            assertNull(LanSocketBinder.currentHost())
            LanSocketBinder.Factory(500, defaultHost = "192.168.31.1").createSocket().close()
            assertEquals("192.168.31.1", seen.get())
        } finally {
            LanSocketBinder.resolver = prev
            LanSocketBinder.socketFactoryFor = prevSf
        }
    }

    @Test fun factoryPrefersNetworkSocketFactory() {
        val used = AtomicReference(false)
        val prev = LanSocketBinder.resolver
        val prevSf = LanSocketBinder.socketFactoryFor
        try {
            LanSocketBinder.resolver = { null }
            LanSocketBinder.socketFactoryFor = { host ->
                assertEquals("10.1.1.1", host)
                object : SocketFactory() {
                    override fun createSocket(): Socket {
                        used.set(true)
                        return Socket()
                    }
                    override fun createSocket(h: String, p: Int) = error("unused")
                    override fun createSocket(h: String, p: Int, l: java.net.InetAddress?, lp: Int) = error("unused")
                    override fun createSocket(a: java.net.InetAddress, p: Int) = error("unused")
                    override fun createSocket(a: java.net.InetAddress, p: Int, l: java.net.InetAddress?, lp: Int) = error("unused")
                }
            }
            LanSocketBinder.withDestination("10.1.1.1") {
                LanSocketBinder.Factory(500).createSocket().close()
            }
            assertTrue(used.get())
        } finally {
            LanSocketBinder.resolver = prev
            LanSocketBinder.socketFactoryFor = prevSf
        }
    }

    @Test fun nullResolverIsNoop() {
        val prev = LanSocketBinder.resolver
        val prevSf = LanSocketBinder.socketFactoryFor
        try {
            LanSocketBinder.resolver = null
            LanSocketBinder.socketFactoryFor = null
            LanSocketBinder.Factory(500).createSocket().close()
        } finally {
            LanSocketBinder.resolver = prev
            LanSocketBinder.socketFactoryFor = prevSf
        }
    }

    @Test fun datagramBinderInvoked() {
        val seen = AtomicReference(false)
        val prev = LanSocketBinder.datagramBinder
        try {
            LanSocketBinder.datagramBinder = { seen.set(true) }
            java.net.DatagramSocket().use { LanSocketBinder.applyDatagram(it) }
            assertTrue(seen.get())
        } finally {
            LanSocketBinder.datagramBinder = prev
        }
    }
}
