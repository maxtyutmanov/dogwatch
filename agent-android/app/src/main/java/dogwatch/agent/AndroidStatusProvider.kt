package dogwatch.agent

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import dogwatch.core.model.BatteryStatus
import dogwatch.core.model.CameraStatus
import dogwatch.core.model.DeviceInfo
import dogwatch.core.model.DeviceStatus
import dogwatch.core.model.NetworkStatus
import dogwatch.core.net.AddressKind
import dogwatch.core.net.Addresses
import dogwatch.core.server.StatusProvider
import java.net.Inet4Address
import java.net.NetworkInterface

class AndroidStatusProvider(private val context: Context, private val graph: AgentGraph) : StatusProvider {

    override suspend fun current(): DeviceStatus = snapshot()

    /** Cheap, non-blocking reads only, so the setup screen can call it on the main thread. */
    fun snapshot(): DeviceStatus {
        val battery = battery()
        val network = network()
        val permissions = permissions()
        return DeviceStatus(
            device = DeviceInfo(Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
            battery = battery,
            network = network,
            storageFreeMb = StatFs(context.filesDir.path).availableBytes / (1024 * 1024),
            deviceUptimeSec = SystemClock.elapsedRealtime() / 1000,
            agentUptimeSec = graph.serviceStartedAt.takeIf { it > 0 }?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0,
            permissions = permissions,
            camera = CameraStatus(
                state = if (permissions["camera"] == true) "idle" else "unavailable",
                lastOpenProbe = graph.probes.latest(),
            ),
            warnings = warnings(battery, network, permissions),
            agentVersion = BuildConfig.VERSION_NAME,
        )
    }

    private fun battery(): BatteryStatus {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        return BatteryStatus(
            level = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            // "Plugged in" rather than "charging": a full battery on the charger isn't a problem.
            charging = plugged,
            tempC = if (temp == Int.MIN_VALUE) null else temp / 10.0,
        )
    }

    @Suppress("DEPRECATION") // allNetworks / connectionInfo: still the simplest way on API 26-35
    private fun network(): NetworkStatus {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        // The *active* network is Tailscale's VPN, so look for the underlying Wi-Fi network explicitly.
        val wifiCaps = cm?.allNetworks?.mapNotNull { cm.getNetworkCapabilities(it) }
            ?.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
        var rssi: Int? = null
        if (wifiCaps != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                rssi = wifiCaps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
            }
            if (rssi == null) {
                rssi = context.getSystemService(WifiManager::class.java)?.connectionInfo?.rssi?.takeIf { it > -127 && it < 0 }
            }
        }
        return NetworkStatus(
            wifiConnected = wifiCaps != null,
            wifiRssi = rssi,
            tailnetAddress = tailnetAddress(),
            tailscaleInstalled = isInstalled("com.tailscale.ipn"),
        )
    }

    fun permissions(): Map<String, Boolean> {
        val map = linkedMapOf(
            "camera" to granted(Manifest.permission.CAMERA),
            "microphone" to granted(Manifest.permission.RECORD_AUDIO),
            "postNotifications" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                granted(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                context.service<NotificationManager>().areNotificationsEnabled()
            },
            "batteryUnrestricted" to context.service<PowerManager>().isIgnoringBatteryOptimizations(context.packageName),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            map["hibernationExempt"] = context.packageManager.isAutoRevokeWhitelisted
        }
        return map
    }

    private fun warnings(battery: BatteryStatus, network: NetworkStatus, perms: Map<String, Boolean>): List<String> = buildList {
        graph.serverError?.let { add("The agent's web server failed to start: $it") }
        if (network.tailnetAddress == null) {
            add(if (network.tailscaleInstalled) "Tailscale isn't connected on the phone." else "Tailscale isn't installed on the phone.")
        }
        if (!battery.charging) add("The phone isn't plugged in.")
        if ((battery.tempC ?: 0.0) >= 42.0) add("The phone is hot (${battery.tempC} °C).")
        if (perms["camera"] == false) add("Camera permission is missing.")
        if (perms["batteryUnrestricted"] == false) add("Set Dogwatch's battery usage to \"Unrestricted\" so Android doesn't stop it.")
        if (perms["hibernationExempt"] == false) add("Turn off \"Pause app activity if unused\" so Android doesn't revoke permissions.")
    }

    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    companion object {
        /** The phone's 100.x.y.z Tailscale address, if the VPN interface is up. */
        fun tailnetAddress(): String? = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && Addresses.classify(it) == AddressKind.TAILNET }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }
}
