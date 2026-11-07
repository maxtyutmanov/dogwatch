package dogwatch.core.server

import dogwatch.core.net.AddressKind
import dogwatch.core.net.Addresses

/** Pure decision functions used by the server's request pipeline. Kept separate for unit testing. */
object RequestGuards {

    /**
     * Should a connection that arrived on local address [localAddress] be served?
     * Loopback and tailnet: always. LAN: only if [allowLan]. Anything else (e.g. a mobile-data IP): never.
     */
    fun isAllowedInterface(localAddress: String, allowLan: Boolean): Boolean {
        if (localAddress.equals("localhost", ignoreCase = true)) return true
        return when (Addresses.classify(localAddress)) {
            AddressKind.LOOPBACK, AddressKind.TAILNET -> true
            AddressKind.LAN -> allowLan
            AddressKind.OTHER -> false
        }
    }

    /**
     * DNS-rebinding protection: only accept Host values we could legitimately be reached by:
     * IP literals, `localhost`, single-label MagicDNS names (`old-phone`) and `*.ts.net` names.
     */
    fun isAllowedHost(hostHeader: String?): Boolean {
        if (hostHeader.isNullOrBlank()) return false
        val host = stripPort(hostHeader).lowercase().trimEnd('.')
        if (host.isEmpty()) return false
        if (host == "localhost") return true
        if (Addresses.parseLiteral(host) != null) return true
        val label = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")
        if (label.matches(host)) return true
        if (host.endsWith(".ts.net") && host.split('.').all { label.matches(it) }) return true
        return false
    }

    /**
     * CSRF / cross-site WebSocket protection. Browsers always send Origin on cross-origin requests,
     * POSTs and WebSocket handshakes. If present, it must point at the same host:port the request
     * was sent to. Requests without Origin (curl, scripts) are allowed: auth still applies.
     */
    fun isAllowedOrigin(originHeader: String?, hostHeader: String?): Boolean {
        if (originHeader == null) return true
        if (hostHeader == null) return false
        val originHostPort = originHeader.substringAfter("://", missingDelimiterValue = "").trimEnd('/')
        if (originHostPort.isEmpty()) return false // includes "null"
        return normalizeHostPort(originHostPort, originHeader.startsWith("https")) ==
            normalizeHostPort(hostHeader, originHeader.startsWith("https"))
    }

    private fun normalizeHostPort(hostPort: String, https: Boolean): String {
        val host = stripPort(hostPort).lowercase()
        val port = portOf(hostPort) ?: if (https) 443 else 80
        return "$host:$port"
    }

    private fun stripPort(hostPort: String): String =
        if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]"
        else if (hostPort.count { it == ':' } == 1) hostPort.substringBefore(':')
        else hostPort

    private fun portOf(hostPort: String): Int? {
        val tail = if (hostPort.startsWith("[")) hostPort.substringAfter("]", "").removePrefix(":")
        else if (hostPort.count { it == ':' } == 1) hostPort.substringAfter(':')
        else ""
        return tail.toIntOrNull()
    }
}
