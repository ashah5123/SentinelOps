import { useState } from "react";
import { useParams } from "react-router-dom";
import { useApiClient } from "../api/ApiClientProvider";
import {
  assignIncident,
  getIncident,
  getIncidentAuditEvents,
  getIncidentTimeline,
  transitionIncident,
} from "../api/incidents";
import { useAsyncData } from "../hooks/useAsyncData";
import { AsyncBoundary } from "../components/AsyncBoundary";
import { SeverityBadge } from "../components/SeverityBadge";
import { StatusBadge } from "../components/StatusBadge";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { useAnnounce } from "../components/Announcer";
import { useAuth } from "../auth/AuthProvider";
import { hasAnyRole, hasRole } from "../lib/roles";
import { ALLOWED_TRANSITIONS, type IncidentStatus } from "../api/types";
import { ApiError } from "../api/client";

const CONSEQUENTIAL_TRANSITIONS: IncidentStatus[] = ["RESOLVED", "FAILED"];

export function IncidentDetail() {
  const { id } = useParams<{ id: string }>();
  const client = useApiClient();
  const auth = useAuth();
  const announce = useAnnounce();
  const canWrite = hasAnyRole(auth.roles, ["RESPONDER", "ADMIN"]);
  const canReadAudit = hasRole(auth.roles, "ADMIN");

  const [pendingTransition, setPendingTransition] = useState<IncidentStatus | null>(null);
  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [assigneeInput, setAssigneeInput] = useState("");

  const incidentState = useAsyncData((signal) => getIncident(client, id!, signal), [client, id]);
  const timelineState = useAsyncData(
    (signal) => getIncidentTimeline(client, id!, signal),
    [client, id],
  );
  const auditState = useAsyncData(
    (signal) =>
      canReadAudit ? getIncidentAuditEvents(client, id!, signal) : Promise.resolve(null),
    [client, id, canReadAudit],
  );

  async function submitTransition(status: IncidentStatus) {
    if (submitting) return; // prevent duplicate submissions while pending
    setSubmitting(true);
    setActionError(null);
    try {
      await transitionIncident(client, id!, status, reason || `Transitioned to ${status}`);
      announce(`Incident transitioned to ${status}.`);
      setReason("");
      incidentState.refetch();
      timelineState.refetch();
    } catch (err) {
      if (err instanceof ApiError && err.problem?.errorCode === "ILLEGAL_TRANSITION") {
        setActionError(
          "This incident was changed by someone else since you loaded it, or this transition is " +
            "no longer valid. Refreshing the latest state.",
        );
        incidentState.refetch();
      } else {
        setActionError(err instanceof ApiError ? err.message : "The transition failed.");
      }
    } finally {
      setSubmitting(false);
      setPendingTransition(null);
    }
  }

  async function submitAssignment(assigneeId: string | null) {
    if (submitting) return;
    setSubmitting(true);
    setActionError(null);
    try {
      await assignIncident(client, id!, assigneeId);
      announce(assigneeId ? `Incident assigned to ${assigneeId}.` : "Incident unassigned.");
      setAssigneeInput("");
      incidentState.refetch();
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : "The assignment failed.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="incident-detail">
      <AsyncBoundary
        state={incidentState}
        onRetry={incidentState.refetch}
        loadingLabel="Loading incident…"
      >
        {(incident) => (
          <>
            <h1>
              {incident.incidentNumber}: {incident.title}
            </h1>
            <div className="incident-meta">
              <SeverityBadge severity={incident.severity} />
              <StatusBadge status={incident.status} />
            </div>
            <dl className="incident-fields">
              <div>
                <dt>Description</dt>
                <dd>{incident.description || "(none)"}</dd>
              </div>
              <div>
                <dt>Source</dt>
                <dd>{incident.source}</dd>
              </div>
              <div>
                <dt>Affected service</dt>
                <dd>{incident.affectedService}</dd>
              </div>
              <div>
                <dt>Detected</dt>
                <dd>{new Date(incident.detectedAt).toLocaleString()}</dd>
              </div>
              <div>
                <dt>Created</dt>
                <dd>{new Date(incident.createdAt).toLocaleString()}</dd>
              </div>
              <div>
                <dt>Updated</dt>
                <dd>{new Date(incident.updatedAt).toLocaleString()}</dd>
              </div>
              {incident.resolvedAt && (
                <div>
                  <dt>Resolved</dt>
                  <dd>{new Date(incident.resolvedAt).toLocaleString()}</dd>
                </div>
              )}
              <div>
                <dt>Assignee</dt>
                <dd>{incident.assigneeId ?? "Unassigned"}</dd>
              </div>
              <div>
                <dt>Correlation ID</dt>
                <dd>
                  <code>{incident.correlationId}</code>
                </dd>
              </div>
            </dl>

            {actionError && (
              <p role="alert" className="action-error">
                {actionError}
              </p>
            )}

            {canWrite ? (
              <section aria-labelledby="actions-heading">
                <h2 id="actions-heading">Actions</h2>

                <div className="assignment-form">
                  <label htmlFor="assignee-input">Assign to (actor ID)</label>
                  <input
                    id="assignee-input"
                    type="text"
                    value={assigneeInput}
                    onChange={(e) => setAssigneeInput(e.target.value)}
                    placeholder="e.g. responder-demo"
                  />
                  <button
                    type="button"
                    disabled={submitting || !assigneeInput.trim()}
                    onClick={() => submitAssignment(assigneeInput.trim())}
                  >
                    Assign
                  </button>
                  {incident.assigneeId && (
                    <button
                      type="button"
                      disabled={submitting}
                      onClick={() => submitAssignment(null)}
                    >
                      Unassign
                    </button>
                  )}
                </div>

                <div className="transition-form">
                  <label htmlFor="transition-reason">Reason</label>
                  <input
                    id="transition-reason"
                    type="text"
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    placeholder="Optional reason for this transition"
                  />
                  <div className="transition-actions">
                    {ALLOWED_TRANSITIONS[incident.status].length === 0 && (
                      <p>No further transitions are possible from this status.</p>
                    )}
                    {ALLOWED_TRANSITIONS[incident.status].map((target) => (
                      <button
                        key={target}
                        type="button"
                        disabled={submitting}
                        onClick={() => {
                          if (CONSEQUENTIAL_TRANSITIONS.includes(target)) {
                            setPendingTransition(target);
                          } else {
                            void submitTransition(target);
                          }
                        }}
                      >
                        Move to {target}
                      </button>
                    ))}
                  </div>
                </div>
              </section>
            ) : (
              <p className="role-notice">
                Your role ({auth.roles.join(", ") || "none"}) can view this incident but cannot
                perform lifecycle actions.
              </p>
            )}

            <ConfirmDialog
              open={pendingTransition !== null}
              title={`Confirm transition to ${pendingTransition}`}
              description={`This is a consequential action. Are you sure you want to move this incident to ${pendingTransition}?`}
              confirmLabel="Confirm"
              danger={pendingTransition === "FAILED"}
              pending={submitting}
              onConfirm={() => pendingTransition && submitTransition(pendingTransition)}
              onCancel={() => setPendingTransition(null)}
            />

            <section aria-labelledby="timeline-heading">
              <h2 id="timeline-heading">Timeline</h2>
              <AsyncBoundary
                state={timelineState}
                onRetry={timelineState.refetch}
                loadingLabel="Loading timeline…"
                emptyCheck={(entries) => entries.length === 0}
                emptyLabel="No timeline entries yet."
              >
                {(entries) => (
                  <ol className="timeline">
                    {entries.map((entry, i) => (
                      <li key={i}>
                        <time dateTime={entry.occurredAt}>
                          {new Date(entry.occurredAt).toLocaleString()}
                        </time>{" "}
                        — [{entry.type}] {entry.summary}
                      </li>
                    ))}
                  </ol>
                )}
              </AsyncBoundary>
            </section>

            {canReadAudit && (
              <section aria-labelledby="audit-heading">
                <h2 id="audit-heading">Audit history</h2>
                <AsyncBoundary
                  state={auditState}
                  onRetry={auditState.refetch}
                  loadingLabel="Loading audit history…"
                  emptyCheck={(page) => page === null || page.content.length === 0}
                  emptyLabel="No audit records yet."
                >
                  {(page) => (
                    <ul className="audit-list">
                      {page!.content.map((event) => (
                        <li key={event.id}>
                          <time dateTime={event.occurredAt}>
                            {new Date(event.occurredAt).toLocaleString()}
                          </time>{" "}
                          — {event.action} by {event.actorId} ({event.actorType})
                        </li>
                      ))}
                    </ul>
                  )}
                </AsyncBoundary>
              </section>
            )}
          </>
        )}
      </AsyncBoundary>
    </div>
  );
}
