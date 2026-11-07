package dogwatch.core

import dogwatch.core.auth.InMemorySecretStore
import dogwatch.core.auth.PairingManager
import dogwatch.core.auth.RateLimiter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairingManagerTest {
    private var now = 1_000_000L
    private val store = InMemorySecretStore()
    private fun manager() = PairingManager(store, clock = { now }, idleExpiryMs = 10_000)

    @Test
    fun tokenIsStableAndVerifiable() {
        val pm = manager()
        val token = pm.pairingToken()
        assertTrue(token.length >= 43, "256-bit token expected")
        assertEquals(token, pm.pairingToken())
        assertTrue(pm.verifyPairingToken(token))
        assertTrue(pm.verifyPairingToken(" $token\n"))
        assertFalse(pm.verifyPairingToken(token.dropLast(1)))
        assertFalse(pm.verifyPairingToken(""))
    }

    @Test
    fun sessionsAuthenticateAndPersist() {
        val pm = manager()
        val (secret, info) = pm.createSession("Firefox", "100.1.1.1")
        assertEquals(info.id, pm.authenticate(secret)?.id)
        assertNull(pm.authenticate("nope"))
        assertNull(pm.authenticate(null))
        // A fresh manager over the same store still knows the session, but never stores the secret.
        assertEquals(info.id, manager().authenticate(secret)?.id)
        assertFalse(store.get("sessions")!!.contains(secret))
    }

    @Test
    fun revokeAndRotate() {
        val pm = manager()
        val (s1, i1) = pm.createSession("a", "x")
        val (s2, _) = pm.createSession("b", "y")
        assertTrue(pm.revoke(i1.id))
        assertFalse(pm.revoke(i1.id))
        assertNull(pm.authenticate(s1))
        assertNotNull(pm.authenticate(s2))

        val old = pm.pairingToken()
        val new = pm.rotate()
        assertNotEquals(old, new)
        assertFalse(pm.verifyPairingToken(old))
        assertNull(pm.authenticate(s2), "rotation logs everyone out")
        assertTrue(pm.sessions().isEmpty())
    }

    @Test
    fun idleSessionsExpire() {
        val pm = manager()
        val (secret, _) = pm.createSession("a", "x")
        now += 9_000
        assertNotNull(pm.authenticate(secret)) // refreshes last-seen
        now += 9_000
        assertNotNull(pm.authenticate(secret))
        now += 11_000
        assertNull(pm.authenticate(secret))
    }
}

class RateLimiterTest {
    private var now = 0L
    private val rl = RateLimiter(clock = { now }, maxFailures = 3, windowMs = 1_000, baseLockoutMs = 10_000, maxLockoutMs = 25_000)

    @Test
    fun locksOutAfterRepeatedFailuresWithBackoff() {
        repeat(2) { rl.recordFailure("ip") }
        assertEquals(0, rl.retryAfterMs("ip"))
        rl.recordFailure("ip")
        assertEquals(10_000, rl.retryAfterMs("ip"))
        assertEquals(0, rl.retryAfterMs("other-ip"))

        now += 10_000
        assertEquals(0, rl.retryAfterMs("ip"))
        repeat(3) { rl.recordFailure("ip") }
        assertEquals(20_000, rl.retryAfterMs("ip"), "second lockout doubles")

        now += 20_000
        repeat(3) { rl.recordFailure("ip") }
        assertEquals(25_000, rl.retryAfterMs("ip"), "capped")
    }

    @Test
    fun failuresOutsideWindowDontCount() {
        rl.recordFailure("ip")
        rl.recordFailure("ip")
        now += 2_000
        rl.recordFailure("ip")
        assertEquals(0, rl.retryAfterMs("ip"))
    }

    @Test
    fun successResets() {
        repeat(2) { rl.recordFailure("ip") }
        rl.recordSuccess("ip")
        rl.recordFailure("ip")
        assertEquals(0, rl.retryAfterMs("ip"))
    }
}
