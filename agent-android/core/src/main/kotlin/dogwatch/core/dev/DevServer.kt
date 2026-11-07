package dogwatch.core.dev

import dogwatch.core.auth.InMemorySecretStore
import dogwatch.core.auth.PairingManager
import dogwatch.core.model.BatteryStatus
import dogwatch.core.model.CameraProbeResult
import dogwatch.core.model.CameraStatus
import dogwatch.core.model.DeviceInfo
import dogwatch.core.model.DeviceStatus
import dogwatch.core.model.NetworkStatus
import dogwatch.core.server.AgentContext
import dogwatch.core.server.AgentServer
import dogwatch.core.server.StatusProvider
import dogwatch.core.server.WebAssets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Runs the agent server on a laptop with fake device data, for working on the web UI:
 *
 *     (cd web && npm run build) && (cd agent-android && ./gradlew :core:run)
 *
 * Then open the printed pairing URL. Env: DOGWATCH_WEB_DIR (default ../web/dist), DOGWATCH_PORT (8080),
 * DOGWATCH_TOKEN (fixed pairing token, handy for `npm run dev`).
 */
fun main() {
    val webDir = File(System.getenv("DOGWATCH_WEB_DIR") ?: "../web/dist").canonicalFile
    val port = System.getenv("DOGWATCH_PORT")?.toIntOrNull() ?: AgentServer.DEFAULT_PORT
    val store = InMemorySecretStore()
    System.getenv("DOGWATCH_TOKEN")?.let { store.put("pairing_token", it) }
    val pairing = PairingManager(store)
    val started = System.currentTimeMillis()

    val ctx = AgentContext(
        pairing = pairing,
        status = FakeStatus(started),
        web = DirectoryWebAssets(webDir),
        version = "dev",
        allowLan = { true },
    )
    ctx.startStatusTicker(CoroutineScope(Dispatchers.Default), periodMs = 5_000)
    AgentServer(ctx, port).start()

    println("Dogwatch dev server on http://localhost:$port (web UI from $webDir)")
    println("Pair: http://localhost:$port/#pair=${pairing.pairingToken()}")
    Thread.currentThread().join()
}

class DirectoryWebAssets(private val root: File) : WebAssets {
    override fun read(path: String): ByteArray? {
        val f = File(root, path).canonicalFile
        if (!f.path.startsWith(root.path) || !f.isFile) return null
        return f.readBytes()
    }
}

private class FakeStatus(private val started: Long) : StatusProvider {
    override suspend fun current(): DeviceStatus {
        val now = System.currentTimeMillis()
        val t = (now / 1000) % 60
        return DeviceStatus(
            device = DeviceInfo("Google", "Pixel 4a (fake)", "13", 33),
            battery = BatteryStatus(level = 80, charging = true, tempC = 30.0 + t / 10.0),
            network = NetworkStatus(wifiConnected = true, wifiRssi = -50 - t.toInt() % 10, tailnetAddress = "100.101.102.103", tailscaleInstalled = true),
            storageFreeMb = 12_345,
            deviceUptimeSec = 86_400 + (now - started) / 1000,
            agentUptimeSec = (now - started) / 1000,
            permissions = linkedMapOf(
                "camera" to true,
                "microphone" to true,
                "postNotifications" to true,
                "batteryUnrestricted" to true,
                "hibernationExempt" to false,
            ),
            camera = CameraStatus(
                state = "idle",
                lastOpenProbe = CameraProbeResult(at = started, startedFrom = "boot", result = "opened"),
            ),
            warnings = listOf("Turn off \"Pause app activity if unused\" so Android doesn't revoke permissions."),
            agentVersion = "dev",
        )
    }
}
