package com.ghostpanter.files.core.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import com.ghostpanter.files.core.net.LanSocketBinder
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.coroutines.coroutineContext

/**
 * NetBIOS Node Status (NBSTAT, RFC 1002 §4.2.17/4.2.18) over UDP 137. Windows hosts and Samba
 * servers answer with their machine name and workgroup, which mDNS usually does not provide.
 */
object NetBios {
    const val PORT = 137

    data class Name(val name: String, val suffix: Int, val group: Boolean)

    data class Status(val names: List<Name>, val mac: String?) {
        /** Machine name: first unique name with suffix 0x00 (workstation) or 0x20 (server). */
        val hostname: String? get() = (names.firstOrNull { !it.group && it.suffix == 0x00 } ?: names.firstOrNull { !it.group && it.suffix == 0x20 })?.name
        val workgroup: String? get() = names.firstOrNull { it.group && it.suffix == 0x00 }?.name
        /** Suffix 0x20 = File Server Service: the host shares files over SMB. */
        val isFileServer: Boolean get() = names.any { it.suffix == 0x20 }
    }

    /** Builds the 50-byte NBSTAT request for the wildcard name "*". */
    fun query(txId: Int): ByteArray {
        val b = ByteArray(50)
        b[0] = (txId ushr 8).toByte(); b[1] = txId.toByte()
        // flags 0x0000 (query, no recursion), QDCOUNT = 1
        b[5] = 1
        b[12] = 0x20
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() } // "*" padded with NULs
        for (i in 0 until 16) {
            val c = name[i].toInt() and 0xFF
            b[13 + i * 2] = ('A'.code + (c ushr 4)).toByte()
            b[14 + i * 2] = ('A'.code + (c and 0x0F)).toByte()
        }
        b[45] = 0 // end of name
        b[46] = 0; b[47] = 0x21 // QTYPE NBSTAT
        b[48] = 0; b[49] = 0x01 // QCLASS IN
        return b
    }

    /** Parses an NBSTAT response; null when [data] is not a well-formed positive answer. */
    fun parse(data: ByteArray, length: Int = data.size): Status? {
        fun u8(i: Int) = data[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        if (length < 12 + 1 + 10) return null
        val flags = u16(2)
        if (flags and 0x8000 == 0) return null // not a response
        if (flags and 0x000F != 0) return null // RCODE error
        if (u16(6) < 1) return null // ANCOUNT
        var p = 12
        // Answer RR name: either a pointer or a sequence of labels.
        while (p < length) {
            val len = u8(p)
            if (len == 0) { p += 1; break }
            if (len and 0xC0 == 0xC0) { p += 2; break }
            p += 1 + len
        }
        if (p + 10 > length) return null
        val type = u16(p)
        if (type != 0x21) return null
        val rdLen = u16(p + 8)
        p += 10
        if (p + rdLen > length || rdLen < 1) return null
        val count = u8(p); p += 1
        val names = ArrayList<Name>(count)
        for (i in 0 until count) {
            if (p + 18 > length) return null
            val raw = String(data, p, 15, Charsets.ISO_8859_1).trimEnd(' ', '\u0000')
            val suffix = u8(p + 15)
            val nf = u16(p + 16)
            names.add(Name(raw, suffix, nf and 0x8000 != 0))
            p += 18
        }
        val mac = if (p + 6 <= length) (0 until 6).joinToString(":") { "%02x".format(u8(p + it)) }.takeIf { it != "00:00:00:00:00:00" } else null
        return Status(names, mac)
    }

    /**
     * Sends a node-status query to every host and collects answers until [timeoutMs] elapses.
     * Cancellation is honoured between receive timeouts (≤ 200 ms).
     */
    suspend fun scan(hosts: List<String>, timeoutMs: Int = 2000, port: Int = PORT, onAnswer: suspend (ip: String, Status) -> Unit) = withContext(Dispatchers.IO) {
        DatagramSocket().use { sock ->
            // Under a full-tunnel VPN, bind UDP to Wi‑Fi so NBSTAT reaches the LAN (runtime hook).
            LanSocketBinder.applyDatagram(sock)
            sock.soTimeout = 200
            hosts.forEachIndexed { i, h ->
                val q = query(0x4700 + (i and 0xFF))
                runCatching { sock.send(DatagramPacket(q, q.size, InetAddress.getByName(h), port)) }
            }
            val end = System.currentTimeMillis() + timeoutMs
            val buf = ByteArray(1500)
            val seen = HashSet<String>()
            while (System.currentTimeMillis() < end) {
                coroutineContext.ensureActive()
                val pkt = DatagramPacket(buf, buf.size)
                try {
                    sock.receive(pkt)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val ip = pkt.address.hostAddress ?: continue
                if (!seen.add(ip)) continue
                parse(pkt.data, pkt.length)?.let { onAnswer(ip, it) }
            }
        }
    }
}
