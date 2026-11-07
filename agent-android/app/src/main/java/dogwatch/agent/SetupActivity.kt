package dogwatch.agent

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import dogwatch.core.server.AgentServer

/**
 * First-run wizard and status screen on the home phone: permissions checklist, pairing QR code,
 * paired-browser management and the camera-after-restart spike results.
 *
 * Built with plain framework views to keep the APK free of extra UI dependencies.
 */
class SetupActivity : Activity() {
    private lateinit var content: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 3_000)
        }
    }
    private var qrCache: Pair<String, Bitmap>? = null
    private val prefs by lazy { getSharedPreferences("setup", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AgentService.start(this, "activity")
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Re-enter the foreground service so it picks up camera/microphone types now that they're granted.
        AgentService.start(this, "activity")
        render()
    }

    // ---- Rendering ------------------------------------------------------------------------------

    private fun render() {
        val g = graph
        val status = g.status.snapshot()
        val address = status.network.tailnetAddress
        content.removeAllViews()

        content.addView(text("Dogwatch", 26f, bold = true))
        content.addView(
            text(
                when {
                    g.serverError != null -> "Agent error: ${g.serverError}"
                    g.serviceStartedAt > 0 -> "Agent running · v${BuildConfig.VERSION_NAME}"
                    else -> "Agent starting…"
                },
                14f,
                color = if (g.serverError != null) getColor(R.color.bad) else secondaryColor(),
            ),
        )

        // 1. Pairing
        section("Connect a viewer")
        if (address == null) {
            content.addView(text("Connect Tailscale on this phone first. The pairing code appears here once it's connected.", 15f))
        } else {
            val url = "http://$address:${AgentServer.DEFAULT_PORT}/#pair=${g.pairing.pairingToken()}"
            content.addView(text("On your laptop or phone (signed in to the same Tailscale account), scan this code:", 15f))
            content.addView(
                ImageView(this).apply {
                    setImageBitmap(qr(url))
                    adjustViewBounds = true
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    setBackgroundColor(Color.WHITE)
                    contentDescription = "Pairing QR code"
                },
                LinearLayout.LayoutParams(dp(240), dp(240)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(12)
                    bottomMargin = dp(8)
                },
            )
            content.addView(text("Or open http://$address:${AgentServer.DEFAULT_PORT} and paste the link.", 13f, color = secondaryColor()))
            content.addView(
                buttonRow(
                    "Copy link" to { copySensitive(url) },
                    "New code" to { confirmRotate() },
                ),
            )
        }
        val sessions = g.pairing.sessions()
        content.addView(
            text(
                if (sessions.isEmpty()) "No browsers paired yet."
                else "${sessions.size} paired browser(s). Manage them from the web page, or tap New code to log out all of them.",
                13f,
                color = secondaryColor(),
            ),
        )

        // 2. Checklist
        section("Setup checklist")
        tailscaleRow(address, status.network.tailscaleInstalled)
        permissionRow("Camera", "For the live stream.", Manifest.permission.CAMERA)
        permissionRow("Microphone", "For audio in the live stream.", Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionRow("Notifications", "Shows the \"Dogwatch is running\" status.", Manifest.permission.POST_NOTIFICATIONS)
        }
        row(
            "Battery: unrestricted",
            "So Android doesn't stop the agent in the background.",
            status.permissions["batteryUnrestricted"] == true,
            "Allow",
        ) { requestBatteryExemption() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            row(
                "Never pause when unused",
                "Turn off \"Pause app activity if unused\", otherwise Android may revoke permissions after a few months.",
                status.permissions["hibernationExempt"] == true,
                "Open",
            ) {
                startSafely(Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", packageName, null)))
            }
        }
        row(
            "Plugged in",
            "The phone should stay on the charger.",
            status.battery.charging,
            null,
            null,
        )
        if (service<KeyguardManager>().isDeviceSecure) {
            content.addView(
                text(
                    "Note: this phone has a screen lock. After a reboot, the agent only starts once the phone has been unlocked.",
                    13f,
                    color = secondaryColor(),
                ),
            )
        }

        // 3. Options
        section("Options")
        content.addView(
            Switch(this).apply {
                text = "Also allow access from the home Wi-Fi (without Tailscale)"
                isChecked = g.settings.allowLan
                setOnCheckedChangeListener { _, checked -> g.settings.allowLan = checked }
                setPadding(0, dp(8), 0, dp(8))
            },
        )

        // 4. Spike results
        section("Camera after restart")
        content.addView(
            text("Each time the agent starts, it checks whether Android lets it open the camera. Reboot the phone to test the important case.", 13f, color = secondaryColor()),
        )
        val probes = g.probes.all().take(5)
        if (probes.isEmpty()) content.addView(text("No results yet.", 15f))
        probes.forEach { p ->
            val ok = p.result == "opened"
            content.addView(
                text(
                    "${if (ok) "✓" else "✕"} ${p.startedFrom}: ${p.result} · " +
                        DateUtils.getRelativeTimeSpanString(p.at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    15f,
                    color = getColor(if (ok) R.color.ok else R.color.bad),
                ),
            )
        }
    }

    private fun tailscaleRow(address: String?, installed: Boolean) {
        when {
            address != null -> row("Tailscale", "Connected as $address", true, null, null)
            installed -> row("Tailscale", "Installed but not connected. Open it, sign in and turn it on (and enable Always-on VPN).", false, "Open") {
                packageManager.getLaunchIntentForPackage(TAILSCALE)?.let { startSafely(it) }
            }
            else -> row("Tailscale", "Needed to reach this phone from outside your home.", false, "Install") {
                startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$TAILSCALE")))
                    || startSafely(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$TAILSCALE")))
            }
        }
    }

    private fun permissionRow(title: String, detail: String, permission: String) {
        val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        row(title, detail, granted, "Grant") {
            val askedBefore = prefs.getBoolean("asked:$permission", false)
            if (askedBefore && !shouldShowRequestPermissionRationale(permission)) {
                // Denied permanently: the system dialog won't show again, so send the user to app settings.
                startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            } else {
                prefs.edit().putBoolean("asked:$permission", true).apply()
                requestPermissions(arrayOf(permission), 1)
            }
        }
    }

    private fun row(title: String, detail: String, ok: Boolean, actionLabel: String?, action: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        row.addView(
            text(if (ok) "✓" else "!", 18f, bold = true, color = getColor(if (ok) R.color.ok else R.color.bad)),
            LinearLayout.LayoutParams(dp(28), WRAP_CONTENT),
        )
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(title, 16f, bold = !ok))
        texts.addView(text(detail, 13f, color = secondaryColor()))
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        if (!ok && actionLabel != null && action != null) {
            row.addView(Button(this).apply { text = actionLabel; setOnClickListener { action() } })
        }
        content.addView(row)
    }

    // ---- Actions --------------------------------------------------------------------------------

    @SuppressLint("BatteryLife") // Exactly what this app needs: it's a 24/7 agent on a dedicated, plugged-in phone.
    private fun requestBatteryExemption() {
        startSafely(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            || startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    private fun confirmRotate() {
        AlertDialog.Builder(this)
            .setTitle("Create a new pairing code?")
            .setMessage("Every paired browser will be logged out and will need to scan the new code.")
            .setPositiveButton("New code") { _, _ ->
                graph.pairing.rotate()
                qrCache = null
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copySensitive(text: String) {
        val clip = ClipData.newPlainText("Dogwatch pairing link", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Hides the link from the clipboard preview overlay.
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
        service<ClipboardManager>().setPrimaryClip(clip)
    }

    private fun startSafely(intent: Intent): Boolean = try {
        startActivity(intent)
        true
    } catch (_: Exception) {
        false
    }

    // ---- View helpers ---------------------------------------------------------------------------

    private fun qr(text: String): Bitmap {
        qrCache?.takeIf { it.first == text }?.let { return it.second }
        val size = 512
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
        val w = matrix.width
        val h = matrix.height
        val pixels = IntArray(w * h) { i -> if (matrix[i % w, i / w]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888).also { qrCache = text to it }
    }

    private fun section(title: String) {
        content.addView(
            text(title, 18f, bold = true),
            LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(24); bottomMargin = dp(6) },
        )
    }

    private fun buttonRow(vararg buttons: Pair<String, () -> Unit>): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_HORIZONTAL
        buttons.forEach { (label, action) ->
            addView(Button(this@SetupActivity).apply { text = label; setOnClickListener { action() } })
        }
    }

    private fun text(s: CharSequence, sizeSp: Float, bold: Boolean = false, color: Int? = null) = TextView(this).apply {
        text = s
        textSize = sizeSp
        if (bold) setTypeface(typeface, Typeface.BOLD)
        color?.let { setTextColor(it) }
        setLineSpacing(0f, 1.15f)
    }

    private fun secondaryColor(): Int {
        val attrs = obtainStyledAttributes(intArrayOf(android.R.attr.textColorSecondary))
        return attrs.getColor(0, Color.GRAY).also { attrs.recycle() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val TAILSCALE = "com.tailscale.ipn"
    }
}
