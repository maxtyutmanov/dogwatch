# Dogwatch

Turn an old Android phone left at home into a private remote device you can reach from a browser:

- 📹 live camera stream (WebRTC, with an MJPEG fallback)
- ✉️ read SMS
- 🔔 read push notifications

The phone runs a small agent app that serves a web UI and API. You reach it securely
from anywhere over [Tailscale](https://tailscale.com), with no cloud backend and no open ports.

See **[docs/DESIGN.md](docs/DESIGN.md)** for the full design.

Status: design phase (v1).
