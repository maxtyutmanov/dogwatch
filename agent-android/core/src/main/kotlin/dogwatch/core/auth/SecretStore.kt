package dogwatch.core.auth

/**
 * Small persistent key-value store for secrets. On Android this is backed by app-private storage
 * with values encrypted by a Keystore key; on the JVM (tests, dev server) it's in memory.
 */
interface SecretStore {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}

class InMemorySecretStore : SecretStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
}
