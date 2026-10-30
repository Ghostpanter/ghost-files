package com.ghostpanter.files.lan

import com.ghostpanter.files.t

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.ghostpanter.files.core.lan.LanHost
import com.ghostpanter.files.core.lan.LanResults
import com.ghostpanter.files.core.lan.LanService
import com.ghostpanter.files.core.lan.LanServiceKind
import com.ghostpanter.files.core.lan.LanSource
import com.ghostpanter.files.core.lan.NetBios
import com.ghostpanter.files.core.lan.PortSweep
import com.ghostpanter.files.core.lan.ServiceProbe
import com.ghostpanter.files.core.lan.Subnet
import com.ghostpanter.files.privilege.WifiBypass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

data class ScanState(
    val running: Boolean = false,
    val phase: String = "",
    val progress: Float = 0f,
    val hosts: List<LanHost> = emptyList(),
    val network: String? = null,
    val error: String? = null,
)

/**
 * Finds file servers on the current Wi-Fi/Ethernet LAN with three methods in parallel:
 * mDNS/DNS-SD (NsdManager), NetBIOS node status (UDP 137) and a bounded TCP sweep of the own /24.
 * Everything runs on IO dispatchers; the UI only observes [state]. [stop] cancels all of it.
 *
 * Under a full-tunnel VPN the default network is the tunnel (only VPN addresses). Discovery always
 * prefers the underlying Wi‑Fi / Ethernet [Network] (runtime [WifiBypass.vpnActive] check) so the
 * sweep subnet and mDNS/NetBIOS traffic stay on the real LAN.
 */
class LanScanner(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private var job: Job? = null
    private val results = LanResults()

    private fun publish(hosts: List<LanHost>) = _state.update { it.copy(hosts = hosts) }

    fun start() {
        if (job?.isActive == true) return
        val net = localIpv4()
        if (net == null) {
            _state.value = ScanState(error = t("未连接到局域网（需要 Wi-Fi 或以太网）", "Not on a LAN (Wi-Fi or Ethernet needed)"))
            return
        }
        val (ip, prefix) = net
        _state.value = ScanState(running = true, phase = t("正在扫描…", "Scanning…"), network = "$ip/$prefix", hosts = results.snapshot())
        job = scope.launch(Dispatchers.IO) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val lock = runCatching { wifi.createMulticastLock("ghost-lan-scan").apply { setReferenceCounted(false); acquire() } }.getOrNull()
            try {
                val hosts = Subnet.hosts(ip, prefix)
                coroutineScope {
                    launch { runCatching { mdns() } }
                    launch {
                        runCatching {
                            NetBios.scan(hosts, timeoutMs = 2500) { addr, st ->
                                val svc = if (st.isFileServer) LanService(LanServiceKind.SMB, 445, LanSource.NETBIOS, st.workgroup) else null
                                publish(results.add(addr, hostname = st.hostname, workgroup = st.workgroup, service = svc))
                            }
                        }
                    }
                    launch { sweep(hosts) }
                }
                _state.update { it.copy(running = false, phase = t("扫描完成", "Scan finished"), progress = 1f) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(running = false, phase = t("已停止", "Stopped")) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(running = false, error = e.message ?: e.javaClass.simpleName) }
            } finally {
                runCatching { lock?.release() }
            }
        }
    }

    fun stop() { job?.cancel(); _state.update { it.copy(running = false, phase = t("已停止", "Stopped")) } }

    fun clear() { stop(); _state.value = ScanState() }

    private suspend fun sweep(hosts: List<String>) = coroutineScope {
        val probes = Semaphore(16)
        PortSweep.sweep(
            hosts, PortSweep.DEFAULT_PORTS, timeoutMs = 350, concurrency = 64,
            onProgress = { done, total -> if (done % 32 == 0 || done == total) _state.update { it.copy(progress = done.toFloat() / total) } },
        ) { host, port ->
            launch {
                probes.withPermit {
                    val svc = withContext(Dispatchers.IO) { ServiceProbe.identify(host, port, 1500) }
                    if (svc != null) publish(results.add(host, service = svc))
                }
            }
        }
    }

    // ------------------------------------------------------------------ mDNS

    private val mdnsTypes = listOf(
        "_smb._tcp." to LanServiceKind.SMB,
        "_ftp._tcp." to LanServiceKind.FTP,
        "_sftp-ssh._tcp." to LanServiceKind.SFTP,
        "_ssh._tcp." to LanServiceKind.SFTP,
        "_webdav._tcp." to LanServiceKind.WEBDAV,
        "_webdavs._tcp." to LanServiceKind.WEBDAVS,
    )

    /** Discovers for ~8 s; resolves one service at a time (NsdManager allows only one resolve in flight before API 34). */
    private suspend fun mdns() = coroutineScope {
        val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val found = Channel<Pair<NsdServiceInfo, LanServiceKind>>(Channel.UNLIMITED)
        val listeners = ArrayList<NsdManager.DiscoveryListener>()
        // Under VPN, scope discovery to the Wi‑Fi network (API 33+) so mDNS is not swallowed by the tunnel.
        val wifiNet = if (WifiBypass.vpnActive(context)) WifiBypass.wifiNetwork(context) else null
        for ((type, kind) in mdnsTypes) {
            val l = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(info: NsdServiceInfo) { found.trySend(info to kind) }
                override fun onServiceLost(info: NsdServiceInfo) {}
                override fun onDiscoveryStarted(serviceType: String) {}
                override fun onDiscoveryStopped(serviceType: String) {}
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            }
            runCatching {
                if (wifiNet != null && Build.VERSION.SDK_INT >= 33) {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, wifiNet, Runnable::run, l)
                } else {
                    nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l)
                }
                listeners.add(l)
            }
        }
        val resolver = launch {
            for ((info, kind) in found) {
                val r = withTimeoutOrNull(4000) { resolve(nsd, info) } ?: continue
                @Suppress("DEPRECATION")
                val addr = r.host as? Inet4Address ?: continue
                val ip = addr.hostAddress ?: continue
                val path = r.attributes["path"]?.let { String(it, Charsets.UTF_8) }?.takeIf { it.isNotBlank() }
                publish(results.add(ip, hostname = r.serviceName, service = LanService(kind, r.port, LanSource.MDNS, r.serviceName, path)))
            }
        }
        try {
            kotlinx.coroutines.delay(8000)
        } finally {
            listeners.forEach { runCatching { nsd.stopServiceDiscovery(it) } }
            found.close()
        }
        resolver.join()
    }

    @Suppress("DEPRECATION")
    private suspend fun resolve(nsd: NsdManager, info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { c ->
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { if (c.isActive) c.resume(null) }
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) { if (c.isActive) c.resume(serviceInfo) }
            })
        } catch (e: Exception) {
            if (c.isActive) c.resume(null)
        }
    }

    // ------------------------------------------------------------------ network

    /**
     * IPv4 address and prefix of the underlying Wi‑Fi / Ethernet network — never the VPN tunnel.
     * Runtime VPN detection only changes which network we pick; without a VPN the path matches the old logic.
     */
    private fun localIpv4(): Pair<String, Int>? {
        // 1) Explicit Wi‑Fi / Ethernet Network (excludes TRANSPORT_VPN), works under full-tunnel VPN.
        WifiBypass.wifiIpv4(context)?.let { return it }

        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = buildList {
            // Prefer non-VPN LAN networks; activeNetwork under VPN is the tunnel and may also
            // advertise WIFI as an underlying transport — skip any network that has TRANSPORT_VPN.
            @Suppress("DEPRECATION") addAll(cm.allNetworks)
            cm.activeNetwork?.let { add(it) }
        }.distinct()
        for (n in candidates) {
            val caps = cm.getNetworkCapabilities(n) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!lan) continue
            val la: LinkAddress = cm.getLinkProperties(n)?.linkAddresses?.firstOrNull { it.address is Inet4Address } ?: continue
            val ip = la.address.hostAddress ?: continue
            return ip to la.prefixLength
        }
        // Hotspot / some ROMs: fall back to private IPv4 on a LAN-looking interface (never tun/ppp/wg).
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !isLikelyVpnOrMobile(it.name) }
                .flatMap { ni -> ni.interfaceAddresses.map { ni to it } }
                .firstOrNull { (_, ia) -> ia.address is Inet4Address && Subnet.isPrivate(ia.address.hostAddress ?: "") }
                ?.let { (_, ia) -> (ia.address.hostAddress ?: return@let null) to ia.networkPrefixLength.toInt() }
        }.getOrNull()
    }

    private fun isLikelyVpnOrMobile(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") ||
            n.startsWith("ipsec") || n.startsWith("pptp") || n.contains("vpn") ||
            n.startsWith("rmnet") || n.startsWith("ccmni")
    }
}
