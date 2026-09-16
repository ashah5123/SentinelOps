import {
  getIncidentAlertCorrelations,
  getIncidentAlerts,
  getIncidentEscalations,
  getIncidentNotifications,
} from "../api/alerts";
import { useApiClient } from "../api/ApiClientProvider";
import { useAsyncData } from "../hooks/useAsyncData";
import { AsyncBoundary } from "./AsyncBoundary";

/**
 * Read-only alert/notification/escalation context for an incident's detail page (Phase 12,
 * section 13). Purely informational — no action here mutates anything; acting on an incident
 * (transitioning it, which cancels pending escalations) still happens through the normal actions
 * section above.
 */
export function AlertContextPanel({
  incidentId,
  canSeeEscalations,
}: {
  incidentId: string;
  canSeeEscalations: boolean;
}) {
  const client = useApiClient();

  const alertsState = useAsyncData(
    (signal) => getIncidentAlerts(client, incidentId, signal),
    [client, incidentId],
  );
  const correlationsState = useAsyncData(
    (signal) => getIncidentAlertCorrelations(client, incidentId, signal),
    [client, incidentId],
  );
  const notificationsState = useAsyncData(
    (signal) => getIncidentNotifications(client, incidentId, signal),
    [client, incidentId],
  );
  const escalationsState = useAsyncData(
    (signal) =>
      canSeeEscalations ? getIncidentEscalations(client, incidentId, signal) : Promise.resolve([]),
    [client, incidentId, canSeeEscalations],
  );

  return (
    <section aria-labelledby="alert-context-heading" className="alert-context-panel">
      <h2 id="alert-context-heading">Alert Context</h2>

      <AsyncBoundary
        state={alertsState}
        onRetry={alertsState.refetch}
        loadingLabel="Loading alerts…"
        emptyCheck={(alerts) => alerts.length === 0}
        emptyLabel="No alerts have been ingested for this incident."
      >
        {(alerts) => (
          <div className="alert-context-alerts">
            <h3>Originating alerts</h3>
            <ul>
              {alerts.map((alert) => (
                <li key={alert.id}>
                  <strong>{alert.source}</strong> — {alert.alertName} ({alert.status}){" · "}
                  fingerprint <code>{alert.fingerprint.slice(0, 12)}…</code> (v
                  {alert.fingerprintVersion}){" · "}
                  first ingested{" "}
                  <time dateTime={alert.ingestedAt}>
                    {new Date(alert.ingestedAt).toLocaleString()}
                  </time>
                </li>
              ))}
            </ul>
          </div>
        )}
      </AsyncBoundary>

      <AsyncBoundary
        state={correlationsState}
        onRetry={correlationsState.refetch}
        loadingLabel="Loading correlations…"
        emptyCheck={(items) => items.length === 0}
        emptyLabel="No alerts have been correlated into this incident."
      >
        {(correlations) => (
          <div className="alert-context-correlations">
            <h3>Correlated alerts</h3>
            <ul>
              {correlations.map((c) => (
                <li key={c.id}>
                  Rule <code>{c.ruleId}</code> (v{c.ruleVersion}): {c.explanation}
                </li>
              ))}
            </ul>
          </div>
        )}
      </AsyncBoundary>

      <AsyncBoundary
        state={notificationsState}
        onRetry={notificationsState.refetch}
        loadingLabel="Loading notifications…"
        emptyCheck={(items) => items.length === 0}
        emptyLabel="No notifications have been sent for this incident."
      >
        {(notifications) => (
          <div className="alert-context-notifications">
            <h3>Notification delivery</h3>
            <ul>
              {notifications.map((n) => (
                <li key={n.id}>
                  {n.channel} — {n.status} (attempt {n.attemptCount})
                  {n.lastError && <span className="notification-error"> — {n.lastError}</span>}
                </li>
              ))}
            </ul>
          </div>
        )}
      </AsyncBoundary>

      {canSeeEscalations && (
        <AsyncBoundary
          state={escalationsState}
          onRetry={escalationsState.refetch}
          loadingLabel="Loading escalations…"
          emptyCheck={(items) => items.length === 0}
          emptyLabel="No escalations are scheduled for this incident."
        >
          {(escalations) => (
            <div className="alert-context-escalations">
              <h3>Escalations</h3>
              <ul>
                {escalations.map((e) => (
                  <li key={e.id}>
                    {e.status} — scheduled for{" "}
                    <time dateTime={e.scheduledAt}>{new Date(e.scheduledAt).toLocaleString()}</time>
                    {e.cancelledReason && <span> ({e.cancelledReason})</span>}
                  </li>
                ))}
              </ul>
            </div>
          )}
        </AsyncBoundary>
      )}
    </section>
  );
}
