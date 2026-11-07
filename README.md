# Dogwatch

Turn an old Android phone left at home into a private remote device you can reach from a browser:

- 📹 live camera stream (WebRTC, with an MJPEG fallback): *M1 / M4*
- ✉️ read SMS: *M2*
- 🔔 read push notifications: *M3*

The phone runs a small agent app that serves a web UI and API. You reach it securely
from anywhere over [Tailscale](https://tailscale.com), with no cloud backend and no open ports.
See **[docs/DESIGN.md](docs/DESIGN.md)** for the full design.

**Status: Milestone 0 (skeleton).** It covers pairing, the device status page, the setup screen on
the phone, and the camera-after-reboot test. The Live, Messages and Notifications tabs are placeholders.

## Repository layout

| Path | What |
|------|------|
| `agent-android/core` | Pure Kotlin: HTTP/WebSocket server (Ktor), pairing and sessions, audit log, models. Unit-tested on the JVM. |
| `agent-android/app` | The Android app: foreground service, setup screen, device status, camera probe. |
| `web` | The viewer web UI (Preact + TypeScript + Vite), bundled into the APK. |
| `docs/DESIGN.md` | Architecture and milestones. |

## Install on the home phone

1. **Get the APK.** Every push builds one in GitHub Actions: open the latest **CI** run →
   *Artifacts* → `dogwatch-agent-debug-apk`. Or build it yourself (see below).
2. **Install it from a computer** with USB debugging enabled on the phone:
   ```sh
   adb install -r app-debug.apk
   ```
   Android 13 blocks some settings for apps installed from a browser or file manager.
   Installing with `adb` avoids that.
3. **Install Tailscale** on the phone, sign in, and turn on *Always-on VPN*
   (Settings → Network → VPN → Tailscale ⚙).
4. **Open Dogwatch** and work through the checklist: camera, microphone, notifications,
   battery *Unrestricted*, and *Pause app activity if unused* off.
5. **On your laptop or phone,** sign in to Tailscale with the same account and scan the QR code
   shown by Dogwatch. The status page opens.

### Test the camera after a reboot (M0 spike)

Reboot the phone and, if it has a screen lock, unlock it once. Wait about 30 seconds, then open the
web UI → **Status → Camera after restart**. `Camera opened · started at boot` means the camera can
be used after a reboot without anyone touching the phone. Anything else tells us which workaround M1 needs.

## Develop

Requirements: JDK 17+, Node 22+. The Android SDK is needed only for `:app`.

```sh
# Web UI
cd web && npm ci && npm run build

# Agent core: unit tests, and a dev server with fake phone data on http://localhost:8080
cd agent-android
./gradlew :core:test
DOGWATCH_TOKEN=dev ./gradlew :core:run      # then open http://localhost:8080/#pair=dev

# Live-reloading UI against the dev server (or a real phone: DOGWATCH_AGENT=http://100.x.y.z:8080)
cd web && npm run dev

# Android APK (needs ANDROID_HOME or local.properties sdk.dir)
cd agent-android && ./gradlew :app:assembleDebug
```

Without an Android SDK, Gradle builds only `:core`.

### Stable signing key

CI signs the APK with a fresh debug key on every run. Android refuses to install an update signed
with a different key, so you would have to uninstall the app first and lose the pairing and
permissions. To avoid that, give CI a fixed debug key once:

```sh
keytool -genkeypair -keystore debug.keystore -storepass android -alias androiddebugkey \
  -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
base64 -w0 debug.keystore   # add the output as repository secret DEBUG_KEYSTORE_BASE64
```

Keep `debug.keystore` out of the repository.
