import type { AgentEvent, DeviceStatus, SessionInfo } from "./types";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly retryAfterSec?: number,
  ) {
    super(`${status} ${code}`);
  }
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: body === undefined ? undefined : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let code = res.statusText || "error";
    let retryAfterSec: number | undefined;
    try {
      const err = await res.json();
      code = err.error ?? code;
      retryAfterSec = err.retryAfterSec ?? undefined;
    } catch {
      /* not JSON */
    }
    throw new ApiError(res.status, code, retryAfterSec);
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  me: () => request<SessionInfo>("GET", "/api/auth/me"),
  pair: (token: string) => request<SessionInfo>("POST", "/api/auth/pair", { token }),
  logout: () => request<void>("POST", "/api/auth/logout"),
  sessions: () => request<SessionInfo[]>("GET", "/api/auth/sessions"),
  revokeSession: (id: string) => request<void>("DELETE", `/api/auth/sessions/${encodeURIComponent(id)}`),
  status: () => request<DeviceStatus>("GET", "/api/status"),
};

export type ConnectionState = "connecting" | "open" | "closed";

/**
 * Keeps a WebSocket to /api/events open, reconnecting with backoff (1 s → 30 s).
 * Returns a function that closes it for good.
 */
export function connectEvents(
  onEvent: (e: AgentEvent) => void,
  onState: (s: ConnectionState) => void,
): () => void {
  let ws: WebSocket | null = null;
  let stopped = false;
  let delay = 1000;
  let timer: number | undefined;

  const open = () => {
    if (stopped) return;
    onState("connecting");
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/api/events`);
    ws.onopen = () => {
      delay = 1000;
      onState("open");
    };
    ws.onmessage = (msg) => {
      try {
        onEvent(JSON.parse(msg.data) as AgentEvent);
      } catch {
        /* ignore malformed */
      }
    };
    ws.onclose = () => {
      onState("closed");
      if (stopped) return;
      timer = window.setTimeout(open, delay);
      delay = Math.min(delay * 2, 30_000);
    };
  };

  // Reconnect right away when the tab comes back to the foreground (phones suspend sockets).
  const onVisible = () => {
    if (document.visibilityState === "visible" && ws?.readyState === WebSocket.CLOSED) {
      window.clearTimeout(timer);
      delay = 1000;
      open();
    }
  };
  document.addEventListener("visibilitychange", onVisible);
  open();

  return () => {
    stopped = true;
    window.clearTimeout(timer);
    document.removeEventListener("visibilitychange", onVisible);
    ws?.close();
  };
}
