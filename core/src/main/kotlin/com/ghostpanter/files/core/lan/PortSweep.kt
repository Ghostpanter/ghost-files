package com.ghostpanter.files.core.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import com.ghostpanter.files.core.net.LanSocketBinder

/**
 * Bounded, cancellable TCP connect sweep. At most [concurrency] connects are in flight; each one is
 * limited by [timeoutMs], so a /24 with 9 ports (2286 probes) takes roughly
 * 2286 / 64 * 300 ms ≈ 11 s in the worst case and much less when hosts refuse quickly.
 */
object PortSweep {
    /** SMB, NetBIOS-SSN, FTP, SSH/SFTP, HTTP, HTTPS, Synology WebDAV http/https, alt HTTP. */
    val DEFAULT_PORTS = listOf(445, 139, 21, 22, 80, 443, 5005, 5006, 8080)

    fun interface Connector {
        /** Returns true when a TCP connection to [host]:[port] could be opened within [timeoutMs]. */
        fun canConnect(host: String, port: Int, timeoutMs: Int): Boolean
    }

    val tcp = Connector { host, port, timeoutMs ->
        try {
            LanSocketBinder.withDestination(host) {
                Socket().use { s ->
                    s.tcpNoDelay = true
                    LanSocketBinder.apply(s, host)
                    s.connect(InetSocketAddress(host, port), timeoutMs)
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sweep(
        hosts: List<String>,
        ports: List<Int> = DEFAULT_PORTS,
        timeoutMs: Int = 300,
        concurrency: Int = 64,
        connector: Connector = tcp,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onOpen: suspend (host: String, port: Int) -> Unit,
    ) = coroutineScope {
        val sem = Semaphore(concurrency.coerceAtLeast(1))
        val total = hosts.size * ports.size
        val done = java.util.concurrent.atomic.AtomicInteger()
        // Port-major order: the most useful services (SMB first) show up early for every host.
        for (port in ports) for (host in hosts) {
            coroutineContext.ensureActive()
            sem.acquire()
            launch {
                try {
                    val open = withContext(Dispatchers.IO) { connector.canConnect(host, port, timeoutMs) }
                    if (open) onOpen(host, port)
                } finally {
                    sem.release()
                    onProgress(done.incrementAndGet(), total)
                }
            }
        }
    }

}
