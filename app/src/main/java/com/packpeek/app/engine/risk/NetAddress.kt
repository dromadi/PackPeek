package com.packpeek.app.engine.risk

/**
 * Classifies IP literals found in app code. Only a public address can be a remote server that
 * collects data; private, loopback and link-local addresses point at the device itself, the local
 * network or the emulator host, and the big public resolvers are used for connectivity checks.
 */
object NetAddress {
    private val resolvers =
        setOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112", "208.67.222.222", "208.67.220.220")

    /** Octets of a dotted IPv4 literal, or null when [host] is not one. */
    fun ipv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = IntArray(4)
        parts.forEachIndexed { i, p ->
            if (p.isEmpty() || p.length > 3 || !p.all(Char::isDigit)) return null
            octets[i] = p.toInt().takeIf { it <= 255 } ?: return null
        }
        return octets
    }

    fun isIp(host: String): Boolean = ipv4(host) != null || (':' in host && host.all { it.isLetterOrDigit() || it == ':' })

    /** A routable address that is not a well-known resolver. */
    fun isPublic(host: String): Boolean {
        if (host in resolvers) return false
        val o = ipv4(host)
        if (o == null) {
            if (!isIp(host)) return false
            val h = host.lowercase()
            // ::, ::1, link-local fe80::/10, unique-local fc00::/7, multicast ff00::/8.
            return h != "::" && h != "::1" && !h.startsWith("fe8") && !h.startsWith("fe9") &&
                !h.startsWith("fea") && !h.startsWith("feb") && !h.startsWith("fc") &&
                !h.startsWith("fd") && !h.startsWith("ff")
        }
        val (a, b) = o
        return when {
            a == 0 || a == 10 || a == 127 -> false
            a == 100 && b in 64..127 -> false // carrier-grade NAT
            a == 169 && b == 254 -> false // link-local
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a >= 224 -> false // multicast and reserved, including 255.255.255.255
            else -> true
        }
    }
}
