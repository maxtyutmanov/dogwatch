package dogwatch.core.server

import dogwatch.core.audit.AuditLog
import dogwatch.core.auth.PairingManager
import dogwatch.core.auth.RateLimiter
import dogwatch.core.events.EventBus
import dogwatch.core.model.DeviceStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

fun interface StatusProvider {
    suspend fun current(): DeviceStatus
}

/** Source of the bundled web UI files (APK assets on Android, a directory for the dev server). */
fun interface WebAssets {
    /** Returns the file at [path] (relative, e.g. `index.html`, `assets/app.js`), or null. */
    fun read(path: String): ByteArray?
}

val DogwatchJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/** Everything the server needs. Built by the Android service (or the dev server / tests). */
class AgentContext(
    val pairing: PairingManager,
    val status: StatusProvider,
    val web: WebAssets,
    val version: String,
    val bus: EventBus = EventBus(),
    val audit: AuditLog = AuditLog(),
    val rateLimiter: RateLimiter = RateLimiter(),
    /** Whether to also accept connections arriving on LAN addresses (not just the tailnet). */
    val allowLan: () -> Boolean = { false },
) {
    /** Publishes a `status` event every [periodMs] while at least one viewer is connected. */
    fun startStatusTicker(scope: CoroutineScope, periodMs: Long = 30_000): Job = scope.launch {
        while (isActive) {
            delay(periodMs)
            if (bus.subscriberCount() > 0) {
                runCatching { status.current() }.onSuccess {
                    bus.publish("status", DogwatchJson.encodeToJsonElement(DeviceStatus.serializer(), it))
                }
            }
        }
    }
}
