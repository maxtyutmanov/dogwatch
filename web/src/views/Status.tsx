import { useEffect, useState } from "preact/hooks";
import { api, ApiError } from "../api";
import { ago, browserName, duration, megabytes, PERMISSION_LABELS, wifiQuality } from "../format";
import type { CameraProbeResult, DeviceStatus, SessionInfo } from "../types";

interface Props {
  status: DeviceStatus | null;
  onUnauthorized: () => void;
}

export function StatusView({ status, onUnauthorized }: Props) {
  if (!status) return <div class="center muted">Loading status…</div>;
  const { device, battery, network, camera } = status;
  const hot = battery.tempC != null && battery.tempC >= 42;

  return (
    <div class="stack">
      {status.warnings.length > 0 && (
        <section class="banner" role="status">
          <b>Needs attention on the phone</b>
          <ul>
            {status.warnings.map((w) => (
              <li>{w}</li>
            ))}
          </ul>
        </section>
      )}

      <div class="grid">
        <Stat label="Battery" value={`${battery.level}%`} detail={battery.charging ? "Charging" : "On battery"} tone={battery.charging ? "ok" : "warn"} />
        <Stat
          label="Temperature"
          value={battery.tempC != null ? `${battery.tempC.toFixed(1)} °C` : "—"}
          detail={hot ? "Hot: streaming will be throttled" : "Normal"}
          tone={hot ? "bad" : "ok"}
        />
        <Stat
          label="Wi‑Fi"
          value={network.wifiConnected ? wifiQuality(network.wifiRssi) : "Disconnected"}
          detail={network.wifiRssi != null ? `${network.wifiRssi} dBm` : ""}
          tone={network.wifiConnected ? "ok" : "bad"}
        />
        <Stat
          label="Tailscale"
          value={network.tailnetAddress ? "Connected" : network.tailscaleInstalled ? "Not connected" : "Not installed"}
          detail={network.tailnetAddress ?? ""}
          tone={network.tailnetAddress ? "ok" : "bad"}
        />
      </div>

      <section class="card">
        <h2>Phone</h2>
        <dl class="kv">
          <dt>Model</dt>
          <dd>
            {device.manufacturer} {device.model}
          </dd>
          <dt>Android</dt>
          <dd>
            {device.androidVersion} (API {device.sdkInt})
          </dd>
          <dt>Phone uptime</dt>
          <dd>{duration(status.deviceUptimeSec)}</dd>
          <dt>Agent uptime</dt>
          <dd>{duration(status.agentUptimeSec)}</dd>
          <dt>Free storage</dt>
          <dd>{megabytes(status.storageFreeMb)}</dd>
          <dt>Agent version</dt>
          <dd>{status.agentVersion}</dd>
        </dl>
      </section>

      <section class="card">
        <h2>Permissions</h2>
        <ul class="checklist">
          {Object.entries(status.permissions).map(([key, granted]) => (
            <li class={granted ? "yes" : "no"}>
              <span class="mark" aria-hidden="true">{granted ? "✓" : "✕"}</span>
              {PERMISSION_LABELS[key] ?? key}
              <span class="sr-only">{granted ? " granted" : " missing"}</span>
            </li>
          ))}
        </ul>
      </section>

      <section class="card">
        <h2>Camera after restart</h2>
        <p class="muted small">
          Android may block the camera when the agent was started in the background (for example after a reboot). The agent tests this each time it starts.
        </p>
        <ProbeResult probe={camera.lastOpenProbe} />
      </section>

      <Sessions onUnauthorized={onUnauthorized} />
    </div>
  );
}

function Stat({ label, value, detail, tone }: { label: string; value: string; detail: string; tone: "ok" | "warn" | "bad" }) {
  return (
    <section class={`card stat tone-${tone}`}>
      <div class="stat-label">{label}</div>
      <div class="stat-value">{value}</div>
      <div class="stat-detail">{detail || " "}</div>
    </section>
  );
}

const STARTED_FROM: Record<string, string> = {
  activity: "opened from the app",
  boot: "started at boot",
  "system-restart": "restarted by Android",
  update: "restarted after an app update",
  unknown: "unknown start",
};

function ProbeResult({ probe }: { probe: CameraProbeResult | null }) {
  if (!probe) return <p class="muted">No result yet.</p>;
  const ok = probe.result === "opened";
  return (
    <p class={ok ? "probe ok" : "probe bad"}>
      <b>{ok ? "Camera opened" : `Camera blocked (${probe.result})`}</b>
      <span class="muted">
        {" "}
        · {STARTED_FROM[probe.startedFrom] ?? probe.startedFrom} · {ago(probe.at)}
      </span>
    </p>
  );
}

function Sessions({ onUnauthorized }: { onUnauthorized: () => void }) {
  const [sessions, setSessions] = useState<SessionInfo[] | null>(null);
  const [meId, setMeId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const handle = (e: unknown) => {
    if (e instanceof ApiError && e.status === 401) onUnauthorized();
    else setError("Couldn't load paired browsers.");
  };

  const load = () => {
    Promise.all([api.sessions(), api.me()])
      .then(([list, me]) => {
        setSessions([...list].sort((a, b) => b.lastSeenAt - a.lastSeenAt));
        setMeId(me.id);
      })
      .catch(handle);
  };

  useEffect(load, []);

  const revoke = async (s: SessionInfo) => {
    if (s.id === meId) {
      if (!confirm("Log out this browser? You'll need to scan the QR code again.")) return;
      await api.logout().catch(() => undefined);
      onUnauthorized();
      return;
    }
    if (!confirm(`Remove ${browserName(s.userAgent)}? It will need to pair again.`)) return;
    await api.revokeSession(s.id).catch(handle);
    load();
  };

  return (
    <section class="card">
      <h2>Paired browsers</h2>
      {error && <p class="error">{error}</p>}
      {!sessions && !error && <p class="muted">Loading…</p>}
      {sessions && (
        <ul class="sessions">
          {sessions.map((s) => (
            <li>
              <div>
                <div>
                  {browserName(s.userAgent)}
                  {s.id === meId && <span class="badge">This browser</span>}
                </div>
                <div class="muted small">
                  Last seen {ago(s.lastSeenAt)} · from {s.remoteAddress}
                </div>
              </div>
              <button class="secondary" onClick={() => revoke(s)}>
                {s.id === meId ? "Log out" : "Remove"}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
