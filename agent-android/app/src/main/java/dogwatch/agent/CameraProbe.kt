package dogwatch.agent

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.HandlerThread
import dogwatch.core.model.CameraProbeResult
import dogwatch.core.server.DogwatchJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * M0 spike (DESIGN.md §8): since Android 11, a foreground service started while the app is in the
 * background (e.g. from BOOT_COMPLETED) may not be allowed to use the camera. Each time the agent
 * service starts, we briefly open and close the camera and record what happened, together with how
 * the service was started. The result shows up in `/api/status` and on the Status page.
 */
object CameraProbe {

    @SuppressLint("MissingPermission") // checked explicitly below
    suspend fun run(context: Context): String {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return "no-permission"
        }
        val manager = context.getSystemService(CameraManager::class.java) ?: return "no-camera-service"
        val id = try {
            manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: manager.cameraIdList.firstOrNull()
        } catch (e: Exception) {
            return "exception:${e.javaClass.simpleName}:${e.message}"
        } ?: return "no-camera"

        val thread = HandlerThread("camera-probe").apply { start() }
        try {
            return withTimeoutOrNull(8_000) {
                suspendCancellableCoroutine { cont ->
                    fun finish(result: String) {
                        if (cont.isActive) cont.resume(result)
                    }
                    try {
                        manager.openCamera(
                            id,
                            object : CameraDevice.StateCallback() {
                                override fun onOpened(camera: CameraDevice) {
                                    camera.close()
                                    finish("opened")
                                }

                                override fun onDisconnected(camera: CameraDevice) {
                                    camera.close()
                                    finish("disconnected")
                                }

                                override fun onError(camera: CameraDevice, error: Int) {
                                    camera.close()
                                    finish("error:${errorName(error)}")
                                }
                            },
                            Handler(thread.looper),
                        )
                    } catch (e: Exception) {
                        finish("exception:${e.javaClass.simpleName}:${e.message}")
                    }
                }
            } ?: "timeout"
        } finally {
            thread.quitSafely()
        }
    }

    private fun errorName(code: Int) = when (code) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "in-use"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "max-cameras-in-use"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "disabled"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "device"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "service"
        else -> code.toString()
    }
}

/** Keeps the most recent probe results (newest first) across restarts. */
class CameraProbeStore(context: Context) {
    private val prefs = context.getSharedPreferences("camera_probe", Context.MODE_PRIVATE)
    private val serializer = kotlinx.serialization.builtins.ListSerializer(CameraProbeResult.serializer())

    @Synchronized
    fun all(): List<CameraProbeResult> =
        prefs.getString("results", null)
            ?.let { runCatching { DogwatchJson.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    fun latest(): CameraProbeResult? = all().firstOrNull()

    @Synchronized
    fun add(result: CameraProbeResult) {
        val list = (listOf(result) + all()).take(10)
        prefs.edit().putString("results", DogwatchJson.encodeToString(serializer, list)).apply()
    }
}
