import { useEffect, useRef, useState } from "react";
import { useSearchParams, Link } from "react-router-dom";
import { useApiClient } from "../api/ApiClientProvider";
import { listIncidents } from "../api/incidents";
import { useAsyncData } from "../hooks/useAsyncData";
import { usePolling } from "../hooks/usePolling";
import { AsyncBoundary } from "../components/AsyncBoundary";
import { SeverityBadge } from "../components/SeverityBadge";
import { StatusBadge } from "../components/StatusBadge";
import {
  ALLOWED_SORTS,
  filtersFromSearchParams,
  filtersToQueryParams,
  filtersToSearchParams,
  type QueueFilters,
} from "../lib/filters";
import { INCIDENT_SEVERITIES, INCIDENT_STATUSES } from "../api/types";

function ageLabel(detectedAt: string): string {
  const ms = Date.now() - new Date(detectedAt).getTime();
  const hours = Math.floor(ms / (1000 * 60 * 60));
  if (hours < 1) return "< 1h";
  if (hours < 48) return `${hours}h`;
  return `${Math.floor(hours / 24)}d`;
}

export function IncidentQueue() {
  const client = useApiClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = filtersFromSearchParams(searchParams);
  const [affectedServiceInput, setAffectedServiceInput] = useState(filters.affectedService ?? "");
  const searchInteractingRef = useRef(false);

  const queryParams = filtersToQueryParams(filters);
  const state = useAsyncData(
    (signal) => listIncidents(client, queryParams, signal),
    [client, JSON.stringify(queryParams)],
  );

  // Bounded polling keeps the queue fresh without a full page reload, but never reorders/replaces
  // the list while the user is actively typing in the search box.
  const polling = usePolling(
    async () => {
      if (searchInteractingRef.current) return;
      state.refetch();
    },
    { baseIntervalMs: 20_000, maxIntervalMs: 90_000, enabled: true },
  );

  // Debounce the free-text affectedService filter — the backend's affectedService filter is an
  // exact-match indexed lookup (see IncidentSpecifications), safe to call per keystroke-settle.
  useEffect(() => {
    const handle = setTimeout(() => {
      updateFilters({ ...filters, affectedService: affectedServiceInput || undefined, page: 0 });
    }, 400);
    return () => clearTimeout(handle);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [affectedServiceInput]);

  function updateFilters(next: QueueFilters) {
    setSearchParams(filtersToSearchParams(next));
  }

  return (
    <div className="incident-queue">
      <h1>Incidents</h1>

      <form
        className="filter-panel"
        aria-label="Incident filters"
        onSubmit={(e) => e.preventDefault()}
      >
        <label htmlFor="filter-status">
          Status
          <select
            id="filter-status"
            value={filters.status ?? ""}
            onChange={(e) =>
              updateFilters({
                ...filters,
                status: (e.target.value || undefined) as QueueFilters["status"],
                page: 0,
              })
            }
          >
            <option value="">All</option>
            {INCIDENT_STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>

        <label htmlFor="filter-severity">
          Severity
          <select
            id="filter-severity"
            value={filters.severity ?? ""}
            onChange={(e) =>
              updateFilters({
                ...filters,
                severity: (e.target.value || undefined) as QueueFilters["severity"],
                page: 0,
              })
            }
          >
            <option value="">All</option>
            {INCIDENT_SEVERITIES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>

        <label htmlFor="filter-service">
          Affected service
          <input
            id="filter-service"
            type="text"
            value={affectedServiceInput}
            onFocus={() => (searchInteractingRef.current = true)}
            onBlur={() => (searchInteractingRef.current = false)}
            onChange={(e) => setAffectedServiceInput(e.target.value)}
            placeholder="e.g. checkout-api"
          />
        </label>

        <label htmlFor="filter-unassigned">
          <input
            id="filter-unassigned"
            type="checkbox"
            checked={filters.unassigned ?? false}
            onChange={(e) => updateFilters({ ...filters, unassigned: e.target.checked, page: 0 })}
          />
          Unassigned only
        </label>

        <label htmlFor="filter-sort">
          Sort
          <select
            id="filter-sort"
            value={filters.sort}
            onChange={(e) => updateFilters({ ...filters, sort: e.target.value, page: 0 })}
          >
            {ALLOWED_SORTS.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </label>
      </form>

      {polling.isBackingOff && (
        <p role="status" className="stale-indicator">
          Live updates are having trouble reaching the server — data shown may be stale.{" "}
          <button type="button" onClick={polling.refreshNow}>
            Refresh now
          </button>
        </p>
      )}

      <AsyncBoundary
        state={state}
        onRetry={state.refetch}
        loadingLabel="Loading incidents…"
        emptyCheck={(page) => page.content.length === 0}
        emptyLabel="No incidents match these filters."
      >
        {(page) => (
          <>
            <p className="result-count">
              {page.totalElements} incident(s) match the applied filters — page {page.page + 1} of{" "}
              {Math.max(page.totalPages, 1)}
            </p>
            <table className="incident-table">
              <caption className="visually-hidden">Incident queue</caption>
              <thead>
                <tr>
                  <th scope="col">Incident</th>
                  <th scope="col">Severity</th>
                  <th scope="col">Status</th>
                  <th scope="col">Assignee</th>
                  <th scope="col">Age</th>
                </tr>
              </thead>
              <tbody>
                {page.content.map((incident) => (
                  <tr key={incident.id}>
                    <th scope="row">
                      <Link to={`/incidents/${incident.id}`}>
                        {incident.incidentNumber}: {incident.title}
                      </Link>
                    </th>
                    <td>
                      <SeverityBadge severity={incident.severity} />
                    </td>
                    <td>
                      <StatusBadge status={incident.status} />
                    </td>
                    <td>{incident.assigneeId ?? "Unassigned"}</td>
                    <td>{ageLabel(incident.detectedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <nav aria-label="Pagination" className="pagination">
              <button
                type="button"
                disabled={filters.page === 0}
                onClick={() => updateFilters({ ...filters, page: filters.page - 1 })}
              >
                Previous
              </button>
              <span>
                Page {filters.page + 1} of {Math.max(page.totalPages, 1)}
              </span>
              <button
                type="button"
                disabled={page.last}
                onClick={() => updateFilters({ ...filters, page: filters.page + 1 })}
              >
                Next
              </button>
            </nav>
          </>
        )}
      </AsyncBoundary>
    </div>
  );
}
