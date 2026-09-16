import type { IncidentStatus } from "../api/types";

const STATUS_META: Record<IncidentStatus, { label: string; symbol: string; className: string }> = {
  DETECTED: { label: "Detected", symbol: "○", className: "badge badge-status-detected" },
  INVESTIGATING: {
    label: "Investigating",
    symbol: "◐",
    className: "badge badge-status-investigating",
  },
  AWAITING_APPROVAL: {
    label: "Awaiting approval",
    symbol: "⏸",
    className: "badge badge-status-awaiting",
  },
  MITIGATING: { label: "Mitigating", symbol: "◔", className: "badge badge-status-mitigating" },
  RESOLVED: { label: "Resolved", symbol: "✓", className: "badge badge-status-resolved" },
  FAILED: { label: "Failed", symbol: "✕", className: "badge badge-status-failed" },
};

export function StatusBadge({ status }: { status: IncidentStatus }) {
  const meta = STATUS_META[status];
  return (
    <span className={meta.className}>
      <span aria-hidden="true">{meta.symbol}</span> {meta.label}
    </span>
  );
}
