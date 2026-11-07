package dogwatch.core.audit

import kotlinx.serialization.Serializable

@Serializable
data class AuditEntry(
    val at: Long,
    /** Session id, or null for unauthenticated requests. */
    val session: String?,
    val remoteAddress: String,
    val method: String,
    val path: String,
    val status: Int,
)

/**
 * Bounded, time-limited log of API access. In-memory for M0, so it is lost when the agent restarts;
 * M5 moves it into Room.
 */
class AuditLog(
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionMs: Long = 7L * 24 * 3600 * 1000,
    private val maxEntries: Int = 5000,
) {
    private val entries = ArrayDeque<AuditEntry>()

    @Synchronized
    fun record(entry: AuditEntry) {
        entries.addLast(entry)
        trim()
    }

    /** Most recent first. */
    @Synchronized
    fun recent(limit: Int): List<AuditEntry> {
        trim()
        return entries.reversed().take(limit.coerceIn(1, maxEntries))
    }

    private fun trim() {
        val cutoff = clock() - retentionMs
        while (entries.isNotEmpty() && (entries.size > maxEntries || entries.first().at < cutoff)) entries.removeFirst()
    }
}
