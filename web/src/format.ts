export function duration(totalSec: number): string {
  const d = Math.floor(totalSec / 86400);
  const h = Math.floor((totalSec % 86400) / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  if (d > 0) return `${d}d ${h}h`;
  if (h > 0) return `${h}h ${m}m`;
  if (m > 0) return `${m}m`;
  return `${Math.floor(totalSec)}s`;
}

export function ago(epochMs: number, now = Date.now()): string {
  const sec = Math.max(0, Math.round((now - epochMs) / 1000));
  if (sec < 45) return "just now";
  return `${duration(sec)} ago`;
}

export function megabytes(mb: number): string {
  return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb} MB`;
}

/** Rough Wi-Fi quality label from RSSI in dBm. */
export function wifiQuality(rssi: number | null): string {
  if (rssi == null) return "unknown";
  if (rssi >= -55) return "excellent";
  if (rssi >= -67) return "good";
  if (rssi >= -75) return "fair";
  return "weak";
}

/** Short human name for a browser user-agent string. */
export function browserName(ua: string): string {
  const os = /Android/.test(ua) ? "Android" : /iPhone|iPad/.test(ua) ? "iOS" : /Mac OS X/.test(ua) ? "macOS" : /Windows/.test(ua) ? "Windows" : /Linux/.test(ua) ? "Linux" : "";
  const br = /Edg\//.test(ua) ? "Edge" : /Firefox\//.test(ua) ? "Firefox" : /Chrome\//.test(ua) ? "Chrome" : /Safari\//.test(ua) ? "Safari" : "";
  const name = [br, os].filter(Boolean).join(" on ");
  return name || ua.slice(0, 40) || "Unknown browser";
}

export const PERMISSION_LABELS: Record<string, string> = {
  camera: "Camera",
  microphone: "Microphone",
  postNotifications: "Show notifications",
  batteryUnrestricted: "Battery: unrestricted",
  hibernationExempt: "Never pause when unused",
};
