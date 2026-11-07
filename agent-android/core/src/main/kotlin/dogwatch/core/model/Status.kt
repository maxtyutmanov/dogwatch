package dogwatch.core.model

import kotlinx.serialization.Serializable

/** Snapshot of the home phone's health, returned by `GET /api/status` and pushed over `/api/events`. */
@Serializable
data class DeviceStatus(
    val device: DeviceInfo,
    val battery: BatteryStatus,
    val network: NetworkStatus,
    val storageFreeMb: Long,
    val deviceUptimeSec: Long,
    val agentUptimeSec: Long,
    val permissions: Map<String, Boolean>,
    val camera: CameraStatus,
    val warnings: List<String> = emptyList(),
    val agentVersion: String,
)

@Serializable
data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidVersion: String,
    val sdkInt: Int,
)

@Serializable
data class BatteryStatus(
    val level: Int,
    val charging: Boolean,
    val tempC: Double?,
)

@Serializable
data class NetworkStatus(
    val wifiConnected: Boolean,
    /** Wi-Fi signal in dBm, if known. */
    val wifiRssi: Int?,
    /** The phone's Tailscale (100.x.y.z) address, if the tailnet interface is up. */
    val tailnetAddress: String?,
    val tailscaleInstalled: Boolean,
)

@Serializable
data class CameraStatus(
    /** `idle`, `streaming`, `unavailable`. Only `idle`/`unavailable` until M1. */
    val state: String,
    val viewers: Int = 0,
    /** Result of the M0 spike: can the camera be opened after the agent was started in the background? */
    val lastOpenProbe: CameraProbeResult? = null,
)

@Serializable
data class CameraProbeResult(
    /** Epoch millis. */
    val at: Long,
    /** How the agent service was started: `activity`, `boot`, `system-restart`, `unknown`. */
    val startedFrom: String,
    /** `opened`, `no-permission`, `error:<code>`, `disconnected`, `timeout`, `exception:<message>`. */
    val result: String,
)
