package dogwatch.core.auth

/**
 * Limits failed pairing attempts per client address.
 *
 * After [maxFailures] failures within [windowMs], the key is locked out. Each further lockout
 * doubles the duration, up to [maxLockoutMs]. A success clears the key's history.
 */
class RateLimiter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 5,
    private val windowMs: Long = 15 * 60_000L,
    private val baseLockoutMs: Long = 15 * 60_000L,
    private val maxLockoutMs: Long = 24 * 3600_000L,
) {
    private data class State(
        val failures: MutableList<Long> = mutableListOf(),
        var lockedUntil: Long = 0,
        var lockouts: Int = 0,
    )

    private val states = HashMap<String, State>()

    /** Milliseconds until [key] may try again, or 0 if it may try now. */
    @Synchronized
    fun retryAfterMs(key: String): Long {
        val s = states[key] ?: return 0
        return (s.lockedUntil - clock()).coerceAtLeast(0)
    }

    @Synchronized
    fun recordFailure(key: String) {
        val now = clock()
        val s = states.getOrPut(key) { State() }
        s.failures.removeAll { now - it > windowMs }
        s.failures.add(now)
        if (s.failures.size >= maxFailures) {
            val duration = (baseLockoutMs shl s.lockouts.coerceAtMost(20)).coerceAtMost(maxLockoutMs)
            s.lockedUntil = now + duration
            s.lockouts++
            s.failures.clear()
        }
        if (states.size > 1000) states.entries.removeIf { now - (it.value.failures.lastOrNull() ?: 0) > maxLockoutMs }
    }

    @Synchronized
    fun recordSuccess(key: String) {
        states.remove(key)
    }
}
