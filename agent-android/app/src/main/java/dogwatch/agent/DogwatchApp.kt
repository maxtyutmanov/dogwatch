package dogwatch.agent

import android.app.Application
import android.content.Context
import android.content.res.AssetManager
import dogwatch.core.auth.PairingManager
import dogwatch.core.server.AgentContext
import dogwatch.core.server.WebAssets

class DogwatchApp : Application() {
    val graph: AgentGraph by lazy { AgentGraph(this) }
}

val Context.graph: AgentGraph get() = (applicationContext as DogwatchApp).graph

/** Process-wide singletons shared by the service (which runs the server) and the setup screen. */
class AgentGraph(app: Context) {
    val settings = AgentSettings(app)
    val pairing = PairingManager(KeystoreSecretStore(app))
    val probes = CameraProbeStore(app)

    /** Set by [AgentService] when the server starts; 0 while it isn't running. */
    @Volatile var serviceStartedAt: Long = 0

    /** Last server start failure (e.g. port in use), shown as a warning. */
    @Volatile var serverError: String? = null

    val status = AndroidStatusProvider(app, this)

    val agent = AgentContext(
        pairing = pairing,
        status = status,
        web = AssetWebAssets(app.assets),
        version = BuildConfig.VERSION_NAME,
        allowLan = { settings.allowLan },
    )
}

/** Serves the bundled web UI from `assets/web/`. */
private class AssetWebAssets(private val assets: AssetManager) : WebAssets {
    override fun read(path: String): ByteArray? =
        try {
            assets.open("web/$path").use { it.readBytes() }
        } catch (_: java.io.IOException) {
            null
        }
}

class AgentSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Also serve on the home Wi-Fi's LAN address, not only on the Tailscale address. Off by default. */
    var allowLan: Boolean
        get() = prefs.getBoolean("allow_lan", false)
        set(value) = prefs.edit().putBoolean("allow_lan", value).apply()
}

/** Typed system service lookup; these services always exist on a real phone. */
inline fun <reified T : Any> Context.service(): T =
    getSystemService(T::class.java) ?: error("System service ${T::class.java.simpleName} unavailable")
