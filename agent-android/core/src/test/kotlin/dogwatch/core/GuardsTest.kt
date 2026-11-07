package dogwatch.core

import dogwatch.core.net.AddressKind
import dogwatch.core.net.Addresses
import dogwatch.core.server.RequestGuards
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AddressesTest {
    @Test
    fun classifiesAddresses() {
        assertEquals(AddressKind.LOOPBACK, Addresses.classify("127.0.0.1"))
        assertEquals(AddressKind.LOOPBACK, Addresses.classify("::1"))
        assertEquals(AddressKind.TAILNET, Addresses.classify("100.64.0.1"))
        assertEquals(AddressKind.TAILNET, Addresses.classify("100.127.255.254"))
        assertEquals(AddressKind.OTHER, Addresses.classify("100.128.0.1"))
        assertEquals(AddressKind.OTHER, Addresses.classify("100.63.255.255"))
        assertEquals(AddressKind.TAILNET, Addresses.classify("fd7a:115c:a1e0::1234"))
        assertEquals(AddressKind.TAILNET, Addresses.classify("[fd7a:115c:a1e0:ab12::1]"))
        assertEquals(AddressKind.LAN, Addresses.classify("192.168.1.20"))
        assertEquals(AddressKind.LAN, Addresses.classify("10.0.0.5"))
        assertEquals(AddressKind.LAN, Addresses.classify("172.20.1.1"))
        assertEquals(AddressKind.LAN, Addresses.classify("fe80::1%wlan0"))
        assertEquals(AddressKind.LAN, Addresses.classify("fd00::1"))
        assertEquals(AddressKind.OTHER, Addresses.classify("8.8.8.8"))
        assertEquals(AddressKind.OTHER, Addresses.classify("2001:db8::1"))
        assertEquals(AddressKind.TAILNET, Addresses.classify("::ffff:100.64.1.1"))
    }

    @Test
    fun neverResolvesHostnames() {
        assertNull(Addresses.parseLiteral("example.com"))
        assertNull(Addresses.parseLiteral("localhost"))
        assertNull(Addresses.parseLiteral("deadbeef"))
        assertNull(Addresses.parseLiteral("1.2.3.999"))
        assertNull(Addresses.parseLiteral(""))
    }
}

class RequestGuardsTest {
    @Test
    fun interfacePolicy() {
        assertTrue(RequestGuards.isAllowedInterface("100.100.1.2", allowLan = false))
        assertTrue(RequestGuards.isAllowedInterface("127.0.0.1", allowLan = false))
        assertTrue(RequestGuards.isAllowedInterface("localhost", allowLan = false))
        assertFalse(RequestGuards.isAllowedInterface("192.168.1.20", allowLan = false))
        assertTrue(RequestGuards.isAllowedInterface("192.168.1.20", allowLan = true))
        assertFalse(RequestGuards.isAllowedInterface("31.13.64.35", allowLan = true))
        assertFalse(RequestGuards.isAllowedInterface("some-hostname", allowLan = true))
    }

    @Test
    fun hostHeaders() {
        assertTrue(RequestGuards.isAllowedHost("100.101.102.103:8080"))
        assertTrue(RequestGuards.isAllowedHost("localhost:8080"))
        assertTrue(RequestGuards.isAllowedHost("old-phone:8080"))
        assertTrue(RequestGuards.isAllowedHost("old-phone.tail1234.ts.net:8080"))
        assertTrue(RequestGuards.isAllowedHost("[fd7a:115c:a1e0::1]:8080"))
        assertTrue(RequestGuards.isAllowedHost("192.168.1.5"))
        assertFalse(RequestGuards.isAllowedHost("evil.example.com:8080"))
        assertFalse(RequestGuards.isAllowedHost("ts.net.evil.com"))
        assertFalse(RequestGuards.isAllowedHost(""))
        assertFalse(RequestGuards.isAllowedHost(null))
    }

    @Test
    fun origins() {
        assertTrue(RequestGuards.isAllowedOrigin(null, "old-phone:8080"))
        assertTrue(RequestGuards.isAllowedOrigin("http://old-phone:8080", "old-phone:8080"))
        assertTrue(RequestGuards.isAllowedOrigin("http://100.1.2.3", "100.1.2.3:80"))
        assertFalse(RequestGuards.isAllowedOrigin("http://evil.com", "old-phone:8080"))
        assertFalse(RequestGuards.isAllowedOrigin("http://old-phone:9090", "old-phone:8080"))
        assertFalse(RequestGuards.isAllowedOrigin("null", "old-phone:8080"))
        assertFalse(RequestGuards.isAllowedOrigin("http://old-phone:8080", null))
    }
}
