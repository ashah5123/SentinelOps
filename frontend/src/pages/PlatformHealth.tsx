import { getSloStatus } from "../api/slo";
import { useApiClient } from "../api/ApiClientProvider";
import { useAsyncData } from "../hooks/useAsyncData";
import { AsyncBoundary } from "../components/AsyncBoundary";
import type { BurnRateSeverity } from "../api/types";

const SEVERITY_LABEL: Record<BurnRateSeverity, string> = {
  OK: "OK",
  WATCH: "Watch",
  WARNING: "Warning",
  CRITICAL: "Critical",
  UNKNOWN: "Unknown",
};

/**
 * Platform Health (Phase 15, section: "Failure-aware operator experience"). Every SLO row that
 * cannot be evaluated (Prometheus unreachable, no data yet) shows "Unknown" rather than a
 * fabricated number or a broken page — see {@code SloEvaluationService}'s graceful-degradation
 * contract on the backend. Chaos experiments and dependency degradation are run and observed
 * through infrastructure/docker/scripts/chaos-experiment.sh and Grafana (see
 * docs/validation/chaos-engineering.md) — this page surfaces the SLO/error-budget signal those
 * experiments are expected to move, not a live feed of the experiments themselves.
 */
export function PlatformHealth() {
  const client = useApiClient();
  const state = useAsyncData((signal) => getSloStatus(client, signal), [client]);

  return (
    <div className="platform-health">
      <h1>Platform Health</h1>
      <p className="platform-health-disclaimer">
        Local benchmark/validation signal only — see docs/validation/ for how these numbers are
        produced and their known limitations. Not a production SLA.
      </p>

      <AsyncBoundary
        state={state}
        onRetry={state.refetch}
        loadingLabel="Loading SLO status…"
        emptyCheck={(items) => items.length === 0}
        emptyLabel="No SLOs configured."
      >
        {(slos) => (
          <table className="slo-status-table">
            <thead>
              <tr>
                <th>Service-level objective</th>
                <th>Objective</th>
                <th>Current SLI</th>
                <th>Error budget remaining</th>
                <th>Burn rate</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {slos.map((slo) => (
                <tr key={slo.id} className={`slo-row-${slo.severity.toLowerCase()}`}>
                  <td>
                    <div>{slo.name}</div>
                    <div className="slo-description">{slo.description}</div>
                  </td>
                  <td>{(slo.objective * 100).toFixed(2)}%</td>
                  <td>
                    {slo.known && slo.currentSli !== null
                      ? `${(slo.currentSli * 100).toFixed(3)}%`
                      : "Unknown"}
                  </td>
                  <td>
                    {slo.known && slo.errorBudgetRemaining !== null
                      ? `${(slo.errorBudgetRemaining * 100).toFixed(1)}%`
                      : "Unknown"}
                  </td>
                  <td>
                    {slo.known && slo.burnRate !== null ? slo.burnRate.toFixed(2) + "x" : "Unknown"}
                  </td>
                  <td>
                    <span
                      className={`slo-severity-badge slo-severity-${slo.severity.toLowerCase()}`}
                    >
                      {SEVERITY_LABEL[slo.severity]}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AsyncBoundary>
    </div>
  );
}
