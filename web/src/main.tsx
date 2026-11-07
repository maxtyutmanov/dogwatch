import { render } from "preact";
import { useEffect, useState } from "preact/hooks";
import { api, ApiError, connectEvents, type ConnectionState } from "./api";
import type { DeviceStatus } from "./types";
import { PairView } from "./views/Pair";
import { StatusView } from "./views/Status";
import { ComingSoon } from "./views/ComingSoon";
import { PawIcon } from "./PawIcon";
import "./style.css";

type Auth = "checking" | "unpaired" | "paired";
type Route = "/status" | "/live" | "/messages" | "/notifications";

const TABS: { path: Route; label: string; icon: string }[] = [
  { path: "/live", label: "Live", icon: "●" },
  { path: "/messages", label: "Messages", icon: "✉" },
  { path: "/notifications", label: "Notifications", icon: "🔔" },
  { path: "/status", label: "Status", icon: "♥" },
];

function currentRoute(): Route {
  const p = location.pathname as Route;
  return TABS.some((t) => t.path === p) ? p : "/status";
}

/** Reads and removes `#pair=<token>` from the URL so the token doesn't linger in history. */
function takePairingToken(): string | null {
  const m = location.hash.match(/(?:^#|&)pair=([^&]+)/);
  if (!m) return null;
  history.replaceState(null, "", location.pathname + location.search);
  return decodeURIComponent(m[1]);
}

function App() {
  const [auth, setAuth] = useState<Auth>("checking");
  const [pairError, setPairError] = useState<string | null>(null);
  const [route, setRoute] = useState<Route>(currentRoute());
  const [status, setStatus] = useState<DeviceStatus | null>(null);
  const [conn, setConn] = useState<ConnectionState>("connecting");

  const onUnauthorized = () => {
    setStatus(null);
    setAuth("unpaired");
  };

  // 1. Pair from the QR-code link if present, else check for an existing session.
  useEffect(() => {
    const token = takePairingToken();
    const check = token ? api.pair(token) : api.me();
    check
      .then(() => setAuth("paired"))
      .catch((e) => {
        if (token) setPairError(describePairError(e));
        setAuth("unpaired");
      });

    // A pairing link pasted into an already-open tab only changes the fragment.
    const onHash = () => {
      const t = takePairingToken();
      if (!t) return;
      api
        .pair(t)
        .then(() => {
          setPairError(null);
          setAuth("paired");
        })
        .catch((e) => setPairError(describePairError(e)));
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);

  // 2. Once paired, load status and keep it fresh over the event socket.
  useEffect(() => {
    if (auth !== "paired") return;
    api.status().then(setStatus).catch((e) => e instanceof ApiError && e.status === 401 && onUnauthorized());
    return connectEvents(
      (ev) => {
        if (ev.type === "status") setStatus(ev.data as DeviceStatus);
      },
      setConn,
    );
  }, [auth]);

  useEffect(() => {
    const onPop = () => setRoute(currentRoute());
    window.addEventListener("popstate", onPop);
    return () => window.removeEventListener("popstate", onPop);
  }, []);

  const navigate = (path: Route) => {
    if (path !== location.pathname) history.pushState(null, "", path);
    setRoute(path);
  };

  if (auth === "checking") return <div class="center muted">Connecting to the phone…</div>;

  if (auth === "unpaired") {
    return (
      <PairView
        initialError={pairError}
        onPair={async (token) => {
          await api.pair(token);
          setPairError(null);
          setAuth("paired");
        }}
        describeError={describePairError}
      />
    );
  }

  return (
    <div class="app">
      <header class="topbar">
        <div class="brand">
          <PawIcon />
          <span>Dogwatch</span>
        </div>
        <div class={`conn conn-${conn}`} title="Live updates from the phone">
          <span class="dot" />
          {conn === "open" ? "Connected" : conn === "connecting" ? "Connecting" : "Reconnecting"}
        </div>
      </header>

      <nav class="tabs" aria-label="Sections">
        {TABS.map((t) => (
          <a
            href={t.path}
            class={route === t.path ? "tab active" : "tab"}
            aria-current={route === t.path ? "page" : undefined}
            onClick={(e) => {
              e.preventDefault();
              navigate(t.path);
            }}
          >
            <span class="tab-icon" aria-hidden="true">{t.icon}</span>
            {t.label}
          </a>
        ))}
      </nav>

      <main class="content">
        {route === "/status" && <StatusView status={status} onUnauthorized={onUnauthorized} />}
        {route === "/live" && (
          <ComingSoon
            title="Live camera"
            milestone="M1"
            text="Live video from the phone's camera arrives in the next milestone. For now, the Status tab shows whether the camera can be opened after a reboot."
          />
        )}
        {route === "/messages" && (
          <ComingSoon title="Messages" milestone="M2" text="Read the SMS received by the phone's SIM, with new messages appearing live." />
        )}
        {route === "/notifications" && (
          <ComingSoon title="Notifications" milestone="M3" text="A feed of the notifications shown on the phone, from every app." />
        )}
      </main>
    </div>
  );
}

function describePairError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 429) return `Too many attempts. Try again in ${Math.ceil((e.retryAfterSec ?? 60) / 60)} min.`;
    if (e.status === 401) return "That pairing code isn't valid. It may have been rotated on the phone.";
    if (e.status === 403) return "The phone refused this connection. Are you connected through Tailscale?";
  }
  return "Couldn't reach the phone. Check that it's online and Tailscale is connected.";
}

render(<App />, document.getElementById("app")!);
