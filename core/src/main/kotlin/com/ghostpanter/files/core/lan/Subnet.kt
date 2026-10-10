package com.ghostpanter.files.core.lan

/** IPv4 helpers for choosing which hosts to sweep. */
object Subnet {
    fun toInt(ip: String): Int {
        val p = ip.trim().split('.')
        require(p.size == 4) { "not IPv4: $ip" }
        return p.fold(0) { acc, s -> (acc shl 8) or (s.toInt().also { require(it in 0..255) } and 0xFF) }
    }

    fun toIp(v: Int): String = "${v ushr 24 and 0xFF}.${v ushr 16 and 0xFF}.${v ushr 8 and 0xFF}.${v and 0xFF}"

    /**
     * Hosts to probe for [ip]/[prefix]. Networks larger than a /24 are capped to the device's own /24
     * (a /16 sweep would take minutes and hammer the router); smaller ones are used as-is.
     * Network/broadcast addresses and [ip] itself are excluded.
     */
    fun hosts(ip: String, prefix: Int): List<String> {
        val p = prefix.coerceIn(24, 30)
        val self = toInt(ip)
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        val network = self and mask
        val size = 1 shl (32 - p)
        val out = ArrayList<String>(size)
        for (i in 1 until size - 1) {
            val h = network + i
            if (h != self) out.add(toIp(h))
        }
        return out
    }

    fun isPrivate(ip: String): Boolean = runCatching {
        val v = toInt(ip)
        val a = v ushr 24 and 0xFF
        val b = v ushr 16 and 0xFF
        a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 100 && b in 64..127)
    }.getOrDefault(false)
}
