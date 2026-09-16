import type { IncidentSeverity } from "../api/types";

// Never color alone: every severity/status badge pairs a color with a distinct symbol + text.
const SEVERITY_META: Record<
  IncidentSeverity,
  { label: string; symbol: string; className: string }
> = {
  SEV1: { label: "SEV1 — Critical", symbol: "▲▲", className: "badge badge-sev1" },
  SEV2: { label: "SEV2 — Major", symbol: "▲", className: "badge badge-sev2" },
  SEV3: { label: "SEV3 — Minor", symbol: "●", className: "badge badge-sev3" },
  SEV4: { label: "SEV4 — Info", symbol: "○", className: "badge badge-sev4" },
};

export function SeverityBadge({ severity }: { severity: IncidentSeverity }) {
  const meta = SEVERITY_META[severity];
  return (
    <span className={meta.className}>
      <span aria-hidden="true">{meta.symbol}</span> {meta.label}
    </span>
  );
}
