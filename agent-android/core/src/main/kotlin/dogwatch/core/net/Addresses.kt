package dogwatch.core.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

enum class AddressKind { LOOPBACK, TAILNET, LAN, OTHER }

object Addresses {
    /**
     * Classifies an IP literal. Never performs DNS lookups: non-literal input is [AddressKind.OTHER].
     *
     * - Tailnet: Tailscale's CGNAT range 100.64.0.0/10 and its IPv6 ULA prefix fd7a:115c:a1e0::/48.
     * - LAN: RFC 1918 private IPv4, IPv4 link-local, IPv6 link-local and unique-local (fc00::/7).
     */
    fun classify(literal: String): AddressKind {
        val addr = parseLiteral(literal) ?: return AddressKind.OTHER
        return classify(addr)
    }

    fun classify(addr: InetAddress): AddressKind {
        if (addr.isLoopbackAddress) return AddressKind.LOOPBACK
        val b = addr.address.map { it.toInt() and 0xff }
        when (addr) {
            is Inet4Address -> {
                if (b[0] == 100 && b[1] in 64..127) return AddressKind.TAILNET
                if (addr.isSiteLocalAddress || addr.isLinkLocalAddress) return AddressKind.LAN
            }
            is Inet6Address -> {
                // IPv4-mapped IPv6 (::ffff:a.b.c.d) is decoded by Java into Inet4Address already.
                if (b[0] == 0xfd && b[1] == 0x7a && b[2] == 0x11 && b[3] == 0x5c && b[4] == 0xa1 && b[5] == 0xe0) {
                    return AddressKind.TAILNET
                }
                if (addr.isLinkLocalAddress || (b[0] and 0xfe) == 0xfc) return AddressKind.LAN
            }
            else -> {}
        }
        return AddressKind.OTHER
    }

    /** Parses an IPv4/IPv6 literal (optionally bracketed, optionally with a zone id) without DNS. */
    fun parseLiteral(input: String): InetAddress? {
        var s = input.trim()
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length - 1)
        s = s.substringBefore('%')
        if (s.isEmpty()) return null
        val looksV4 = s.all { it.isDigit() || it == '.' } && s.count { it == '.' } == 3
        val looksV6 = s.contains(':') && s.all { it.isLetterOrDigit() || it == ':' || it == '.' } &&
            s.filter { it.isLetter() }.all { it.lowercaseChar() in 'a'..'f' }
        if (!looksV4 && !looksV6) return null
        if (looksV4 && s.split('.').any { it.isEmpty() || it.length > 3 || it.toInt() > 255 }) return null
        // With an IP literal InetAddress.getByName does not hit DNS.
        return runCatching { InetAddress.getByName(s) }.getOrNull()
    }
}
