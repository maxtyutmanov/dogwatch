// Mirrors agent-android/core/src/main/kotlin/dogwatch/core/model/Status.kt and auth/PairingManager.kt.

export interface DeviceStatus {
  device: { manufacturer: string; model: string; androidVersion: string; sdkInt: number };
  battery: { level: number; charging: boolean; tempC: number | null };
  network: {
    wifiConnected: boolean;
    wifiRssi: number | null;
    tailnetAddress: string | null;
    tailscaleInstalled: boolean;
  };
  storageFreeMb: number;
  deviceUptimeSec: number;
  agentUptimeSec: number;
  permissions: Record<string, boolean>;
  camera: {
    state: string;
    viewers: number;
    lastOpenProbe: CameraProbeResult | null;
  };
  warnings: string[];
  agentVersion: string;
}

export interface CameraProbeResult {
  at: number;
  startedFrom: string;
  result: string;
}

export interface SessionInfo {
  id: string;
  createdAt: number;
  lastSeenAt: number;
  userAgent: string;
  remoteAddress: string;
}

export interface AgentEvent<T = unknown> {
  type: string;
  ts: number;
  data: T;
}
