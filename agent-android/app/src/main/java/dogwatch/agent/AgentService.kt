package dogwatch.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import dogwatch.core.model.CameraProbeResult
import dogwatch.core.model.DeviceStatus
import dogwatch.core.server.AgentServer
import dogwatch.core.server.DogwatchJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Long-running foreground service that owns the HTTP server. Started by [SetupActivity] (app opened)
 * and [BootReceiver] (phone restarted / app updated); restarted by Android if killed (START_STICKY).
 */
class AgentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var server: AgentServer? = null
    private var ticker: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A null intent means Android restarted us after killing the process.
        val startedFrom = intent?.getStringExtra(EXTRA_STARTED_FROM) ?: "system-restart"
        Log.i(TAG, "onStartCommand from=$startedFrom")
        goForeground()

        if (server == null) {
            graph.serviceStartedAt = System.currentTimeMillis()
            acquireLocks()
            startServer()
            ticker = graph.agent.startStatusTicker(scope)
            scope.launch { probeCamera(startedFrom) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        ticker?.cancel()
        server?.let { s -> Thread { s.stop() }.start() }
        server = null
        graph.serviceStartedAt = 0
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun startServer() {
        val s = AgentServer(graph.agent)
        server = s
        // Binding a socket is network I/O: not allowed on the main thread.
        scope.launch(Dispatchers.IO) {
            try {
                s.start()
                graph.serverError = null
                Log.i(TAG, "Server listening on :${AgentServer.DEFAULT_PORT}")
            } catch (e: Exception) {
                graph.serverError = e.message ?: e.javaClass.simpleName
                Log.e(TAG, "Server failed to start", e)
            }
        }
    }

    private suspend fun probeCamera(startedFrom: String) {
        // Give the system a moment after boot before touching the camera.
        delay(if (startedFrom == "boot") 10_000 else 2_000)
        val result = CameraProbe.run(this)
        Log.i(TAG, "Camera probe ($startedFrom): $result")
        graph.probes.add(CameraProbeResult(System.currentTimeMillis(), startedFrom, result))
        runCatching { graph.status.current() }.onSuccess {
            graph.agent.bus.publish("status", DogwatchJson.encodeToJsonElement(DeviceStatus.serializer(), it))
        }
    }

    /**
     * Declares camera/microphone types only once those permissions are granted (Android 14+ throws
     * otherwise). If Android refuses the camera type (background start), fall back to the minimum.
     */
    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification)
            return
        }
        var types = 0
        if (granted(Manifest.permission.CAMERA)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (granted(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        try {
            startForeground(NOTIFICATION_ID, notification, types or base)
        } catch (e: SecurityException) {
            Log.w(TAG, "Camera/mic foreground type refused, continuing without it", e)
            startForeground(NOTIFICATION_ID, notification, base)
        }
    }

    private fun buildNotification(): Notification {
        val nm = service<NotificationManager>()
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.channel_agent), NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, SetupActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val address = AndroidStatusProvider.tailnetAddress()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_paw)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(address?.let { "http://$it:${AgentServer.DEFAULT_PORT}" } ?: "Waiting for Tailscale")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: deprecated on 34+, but LOW_LATENCY only works with the screen on.
    private fun acquireLocks() {
        // The phone is meant to be plugged in, so holding these permanently is acceptable.
        wakeLock = service<PowerManager>()
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "dogwatch:agent")
            .apply { setReferenceCounted(false); acquire() }
        wifiLock = getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "dogwatch:agent")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "AgentService"
        private const val CHANNEL_ID = "agent"
        private const val NOTIFICATION_ID = 1
        const val EXTRA_STARTED_FROM = "startedFrom"

        fun start(context: Context, startedFrom: String) {
            val intent = Intent(context, AgentService::class.java).putExtra(EXTRA_STARTED_FROM, startedFrom)
            context.startForegroundService(intent)
        }
    }
}
