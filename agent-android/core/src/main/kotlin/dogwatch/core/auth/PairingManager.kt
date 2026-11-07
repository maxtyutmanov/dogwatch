package dogwatch.core.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** A paired viewer (browser). The secret cookie value itself is never stored, only its SHA-256. */
@Serializable
data class SessionInfo(
    val id: String,
    val createdAt: Long,
    val lastSeenAt: Long,
    val userAgent: String,
    val remoteAddress: String,
)

@Serializable
private data class StoredSession(
    val info: SessionInfo,
    val secretHash: String,
)

/**
 * Owns the pairing token (shown as a QR code on the phone) and the viewer sessions it grants.
 *
 * Flow: viewer scans the QR code → `POST /api/auth/pair {token}` → [verifyPairingToken] →
 * [createSession] → the cookie value is set as an HttpOnly cookie → each request is checked
 * with [authenticate].
 */
class PairingManager(
    private val store: SecretStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
    /** Sessions unused for this long are dropped. */
    private val idleExpiryMs: Long = 90L * 24 * 3600 * 1000,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var sessions: MutableList<StoredSession> = loadSessions()
    private var lastPersistAt = 0L

    /** The current pairing token, created on first use. */
    fun pairingToken(): String = synchronized(lock) {
        store.get(KEY_TOKEN) ?: newSecret().also { store.put(KEY_TOKEN, it) }
    }

    /** Replaces the pairing token and logs out every viewer. Returns the new token. */
    fun rotate(): String = synchronized(lock) {
        val token = newSecret()
        store.put(KEY_TOKEN, token)
        sessions.clear()
        persist()
        token
    }

    fun verifyPairingToken(candidate: String): Boolean {
        val expected = pairingToken()
        return MessageDigest.isEqual(expected.toByteArray(), candidate.trim().toByteArray())
    }

    /** Creates a session and returns the secret cookie value plus its public info. */
    fun createSession(userAgent: String, remoteAddress: String): Pair<String, SessionInfo> = synchronized(lock) {
        val secret = newSecret()
        val now = clock()
        val info = SessionInfo(
            id = newId(),
            createdAt = now,
            lastSeenAt = now,
            userAgent = userAgent.take(200),
            remoteAddress = remoteAddress,
        )
        sessions.add(StoredSession(info, sha256(secret)))
        while (sessions.size > MAX_SESSIONS) sessions.removeAt(0)
        persist()
        secret to info
    }

    /** Returns the session for a cookie value, or null if unknown/expired. Updates last-seen time. */
    fun authenticate(cookieValue: String?): SessionInfo? {
        if (cookieValue.isNullOrBlank()) return null
        val hash = sha256(cookieValue)
        synchronized(lock) {
            val now = clock()
            dropExpired(now)
            val idx = sessions.indexOfFirst { MessageDigest.isEqual(it.secretHash.toByteArray(), hash.toByteArray()) }
            if (idx < 0) return null
            val updated = sessions[idx].copy(info = sessions[idx].info.copy(lastSeenAt = now))
            sessions[idx] = updated
            // Don't write to flash on every request.
            if (now - lastPersistAt > 60_000) persist()
            return updated.info
        }
    }

    fun sessions(): List<SessionInfo> = synchronized(lock) {
        dropExpired(clock())
        sessions.map { it.info }
    }

    fun revoke(id: String): Boolean = synchronized(lock) {
        val removed = sessions.removeAll { it.info.id == id }
        if (removed) persist()
        removed
    }

    private fun dropExpired(now: Long) {
        if (sessions.removeAll { now - it.info.lastSeenAt > idleExpiryMs }) persist()
    }

    private fun loadSessions(): MutableList<StoredSession> {
        val raw = store.get(KEY_SESSIONS) ?: return mutableListOf()
        return runCatching { json.decodeFromString(ListSerializer(StoredSession.serializer()), raw).toMutableList() }
            .getOrElse { mutableListOf() }
    }

    private fun persist() {
        store.put(KEY_SESSIONS, json.encodeToString(ListSerializer(StoredSession.serializer()), sessions))
        lastPersistAt = clock()
    }

    private fun newSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun newId(): String {
        val bytes = ByteArray(6)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_TOKEN = "pairing_token"
        private const val KEY_SESSIONS = "sessions"
        private const val MAX_SESSIONS = 20

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
