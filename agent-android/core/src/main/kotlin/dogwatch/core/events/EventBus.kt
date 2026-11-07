package dogwatch.core.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Envelope sent to viewers over `/api/events`: `{"type": "...", "ts": 123, "data": {...}}`. */
@Serializable
data class Event(
    val type: String,
    val ts: Long,
    val data: JsonElement,
)

/**
 * In-process fan-out of events to all connected WebSocket clients.
 * Slow consumers never block producers: the oldest buffered events are dropped instead.
 */
class EventBus(private val clock: () -> Long = System::currentTimeMillis) {
    private val flow = MutableSharedFlow<Event>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<Event> = flow.asSharedFlow()

    fun subscriberCount(): Int = flow.subscriptionCount.value

    fun publish(type: String, data: JsonElement) {
        flow.tryEmit(Event(type, clock(), data))
    }
}
