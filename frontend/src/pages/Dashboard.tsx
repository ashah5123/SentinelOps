import { Link } from "react-router-dom";
import { useApiClient } from "../api/ApiClientProvider";
import { getIncidentSummary, listIncidents } from "../api/incidents";
import { useAsyncData } from "../hooks/useAsyncData";
import { AsyncBoundary } from "../components/AsyncBoundary";
import { SeverityBadge } from "../components/SeverityBadge";
import { StatusBadge } from "../components/StatusBadge";
import type { IncidentSeverity, IncidentStatus } from "../api/types";
import { HealthIndicator } from "../components/HealthIndicator";

export function Dashboard() {
  const client = useApiClient();

  const summaryState = useAsyncData((signal) => getIncidentSummary(client, {}, signal), [client]);
  const recentState = useAsyncData(
    (signal) => listIncidents(client, { size: 8, sort: "detectedAt,desc" }, signal),
    [client],
  );
  const unacknowledgedState = useAsyncData(
    (signal) => listIncidents(client, { status: "DETECTED", unassigned: true, size: 5 }, signal),
    [client],
  );

  return (
    <div className="dashboard">
      <div className="dashboard-header">
        <h1>Dashboard</h1>
        <HealthIndicator />
      </div>

      <section aria-labelledby="summary-heading" className="dashboard-summary">
        <h2 id="summary-heading">Summary — all incidents</h2>
        <AsyncBoundary
          state={summaryState}
          onRetry={summaryState.refetch}
          loadingLabel="Loading summary…"
        >
          {(summary) => (
            <div className="summary-tiles">
              <div className="tile">
                <span className="tile-value">{summary.total}</span>
                <span className="tile-label">Total incidents</span>
              </div>
              <div className="tile">
                <span className="tile-value">{summary.open}</span>
                <span className="tile-label">Open (non-terminal)</span>
              </div>
              <div className="tile">
                <span className="tile-value">{summary.unacknowledged}</span>
                <span className="tile-label">Unacknowledged (detected, unassigned)</span>
              </div>
              <div className="tile tile-breakdown">
                <span className="tile-label">By severity</span>
                <ul>
                  {(Object.keys(summary.bySeverity) as IncidentSeverity[]).map((severity) => (
                    <li key={severity}>
                      <SeverityBadge severity={severity} /> {summary.bySeverity[severity]}
                    </li>
                  ))}
                  {Object.keys(summary.bySeverity).length === 0 && <li>None</li>}
                </ul>
              </div>
              <div className="tile tile-breakdown">
                <span className="tile-label">By lifecycle state</span>
                <ul>
                  {(Object.keys(summary.byStatus) as IncidentStatus[]).map((status) => (
                    <li key={status}>
                      <StatusBadge status={status} /> {summary.byStatus[status]}
                    </li>
                  ))}
                  {Object.keys(summary.byStatus).length === 0 && <li>None</li>}
                </ul>
              </div>
            </div>
          )}
        </AsyncBoundary>
      </section>

      <section aria-labelledby="recent-heading">
        <h2 id="recent-heading">Recently detected incidents</h2>
        <AsyncBoundary
          state={recentState}
          onRetry={recentState.refetch}
          loadingLabel="Loading recent incidents…"
          emptyCheck={(page) => page.content.length === 0}
          emptyLabel="No incidents yet."
        >
          {(page) => (
            <>
              <p className="result-count">
                Showing {page.content.length} of {page.totalElements} incident(s)
              </p>
              <ul className="incident-mini-list">
                {page.content.map((incident) => (
                  <li key={incident.id}>
                    <Link to={`/incidents/${incident.id}`}>
                      {incident.incidentNumber}: {incident.title}
                    </Link>
                    <SeverityBadge severity={incident.severity} />
                    <StatusBadge status={incident.status} />
                  </li>
                ))}
              </ul>
            </>
          )}
        </AsyncBoundary>
      </section>

      <section aria-labelledby="unacknowledged-heading">
        <h2 id="unacknowledged-heading">Unacknowledged incidents</h2>
        <AsyncBoundary
          state={unacknowledgedState}
          onRetry={unacknowledgedState.refetch}
          loadingLabel="Loading unacknowledged incidents…"
          emptyCheck={(page) => page.content.length === 0}
          emptyLabel="Nothing unacknowledged — good."
        >
          {(page) => (
            <ul className="incident-mini-list">
              {page.content.map((incident) => (
                <li key={incident.id}>
                  <Link to={`/incidents/${incident.id}`}>
                    {incident.incidentNumber}: {incident.title}
                  </Link>
                  <SeverityBadge severity={incident.severity} />
                </li>
              ))}
            </ul>
          )}
        </AsyncBoundary>
      </section>

      <p>
        <Link to="/incidents">Go to the full incident queue &rarr;</Link>
      </p>
    </div>
  );
}
