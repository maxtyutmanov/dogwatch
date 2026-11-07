package dogwatch.agent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restarts the agent after a reboot or an app update. Both broadcasts are allowed to start a
 * foreground service from the background.
 *
 * Note: BOOT_COMPLETED is only delivered after the phone is unlocked for the first time
 * (DESIGN.md §8), so a phone with a screen lock needs one unlock after each reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val from = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }
        Log.i("BootReceiver", "Starting agent ($from)")
        try {
            AgentService.start(context, from)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Couldn't start agent", e)
        }
    }
}
