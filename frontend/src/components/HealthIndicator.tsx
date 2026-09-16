import { useCallback, useState } from "react";
import { usePolling } from "../hooks/usePolling";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL as string;

type HealthStatus = "UP" | "DOWN" | "UNKNOWN";

/**
 * System-health indicator backed by the real /actuator/health/readiness endpoint (public — see
 * SecurityConfig's PUBLIC_PATHS) — never a fabricated value. Text + symbol, not color alone.
 */
export function HealthIndicator() {
  const [status, setStatus] = useState<HealthStatus>("UNKNOWN");

  const check = useCallback(async () => {
    const response = await fetch(`${API_BASE_URL}/actuator/health/readiness`);
    if (!response.ok) {
      setStatus("DOWN");
      throw new Error("health check failed");
    }
    const body = (await response.json()) as { status?: string };
    setStatus(body.status === "UP" ? "UP" : "DOWN");
  }, []);

  usePolling(check, { baseIntervalMs: 30_000, maxIntervalMs: 120_000, enabled: true });

  const meta =
    status === "UP"
      ? { symbol: "✓", label: "Operational", className: "health-up" }
      : status === "DOWN"
        ? { symbol: "✕", label: "Degraded", className: "health-down" }
        : { symbol: "?", label: "Checking…", className: "health-unknown" };

  return (
    <span className={`health-indicator ${meta.className}`} role="status">
      <span aria-hidden="true">{meta.symbol}</span> System status: {meta.label}
    </span>
  );
}
