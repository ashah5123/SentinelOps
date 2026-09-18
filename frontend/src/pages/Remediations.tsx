import { useState } from "react";
import {
  approveRemediation,
  cancelRemediation,
  emergencyStopRemediation,
  getRemediationSteps,
  listRecentRemediations,
  listRunbooks,
  proposeRemediation,
  rejectRemediation,
} from "../api/remediations";
import { useApiClient } from "../api/ApiClientProvider";
import { useAsyncData } from "../hooks/useAsyncData";
import { AsyncBoundary } from "../components/AsyncBoundary";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { useAnnounce } from "../components/Announcer";
import { useAuth } from "../auth/AuthProvider";
import { hasAnyRole, hasRole } from "../lib/roles";
import { ApiError } from "../api/client";
import type { RemediationExecution, RemediationStep } from "../api/types";

type PendingAction = {
  execution: RemediationExecution;
  action: "approve" | "reject" | "cancel" | "emergency-stop";
};

/**
 * Remediation queue and history (Phase 14, section: "Operator console"). Every action here goes
 * through the same policy-evaluated, approval-gated, auditable path a runbook proposed via MCP
 * does — this page is a thin client over the REST API, nothing more.
 */
export function Remediations() {
  const client = useApiClient();
  const auth = useAuth();
  const announce = useAnnounce();
  const canReview = hasAnyRole(auth.roles, ["RESPONDER", "ADMIN"]);
  const isAdmin = hasRole(auth.roles, "ADMIN");

  const [pending, setPending] = useState<PendingAction | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [selectedSteps, setSelectedSteps] = useState<{
    executionId: string;
    steps: RemediationStep[];
  } | null>(null);
  const [proposeOpen, setProposeOpen] = useState(false);
  const [selectedRunbookSlug, setSelectedRunbookSlug] = useState("");
  const [dryRun, setDryRun] = useState(false);
  const [proposeError, setProposeError] = useState<string | null>(null);

  const runbooksState = useAsyncData((signal) => listRunbooks(client, signal), [client]);
  const executionsState = useAsyncData(
    (signal) => listRecentRemediations(client, signal),
    [client],
  );

  function requestConfirmation(execution: RemediationExecution, action: PendingAction["action"]) {
    setActionError(null);
    setPending({ execution, action });
  }

  async function confirm() {
    if (!pending || submitting) return;
    setSubmitting(true);
    setActionError(null);
    try {
      const { execution, action } = pending;
      if (action === "approve") {
        await approveRemediation(client, execution.id);
        announce("Remediation approved.");
      } else if (action === "reject") {
        await rejectRemediation(client, execution.id);
        announce("Remediation rejected.");
      } else if (action === "cancel") {
        await cancelRemediation(client, execution.id);
        announce("Remediation cancellation requested.");
      } else {
        await emergencyStopRemediation(client, execution.id);
        announce("Emergency stop triggered.");
      }
      executionsState.refetch();
      setPending(null);
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : "The request could not be completed.");
    } finally {
      setSubmitting(false);
    }
  }

  async function viewSteps(execution: RemediationExecution) {
    const steps = await getRemediationSteps(client, execution.id);
    setSelectedSteps({ executionId: execution.id, steps });
  }

  async function submitPropose() {
    if (!selectedRunbookSlug) return;
    setProposeError(null);
    try {
      await proposeRemediation(client, {
        runbookSlug: selectedRunbookSlug,
        dryRun,
        idempotencyKey: crypto.randomUUID(),
      });
      announce(dryRun ? "Dry run proposed." : "Remediation proposed.");
      setProposeOpen(false);
      executionsState.refetch();
    } catch (err) {
      setProposeError(
        err instanceof ApiError ? err.message : "The request could not be completed.",
      );
    }
  }

  return (
    <div className="remediations">
      <h1>Remediations</h1>
      <p className="remediations-disclaimer">
        Every execution below is policy-evaluated and, unless auto-approved for low-risk actions in
        an unrestricted environment, requires human approval before anything real runs. A dry run
        never mutates state.
      </p>

      {canReview && (
        <button type="button" onClick={() => setProposeOpen(true)}>
          Propose remediation
        </button>
      )}

      {actionError && (
        <p role="alert" className="action-error">
          {actionError}
        </p>
      )}

      <AsyncBoundary
        state={executionsState}
        onRetry={executionsState.refetch}
        loadingLabel="Loading remediations…"
        emptyCheck={(items) => items.length === 0}
        emptyLabel="No remediation executions yet."
      >
        {(executions) => (
          <table className="remediations-table">
            <thead>
              <tr>
                <th>Runbook</th>
                <th>Status</th>
                <th>Dry run</th>
                <th>Policy</th>
                <th>Requested by</th>
                <th>Created</th>
                <th>Steps</th>
                {canReview && <th>Actions</th>}
              </tr>
            </thead>
            <tbody>
              {executions.map((execution) => (
                <tr key={execution.id}>
                  <td>{execution.runbookId.slice(0, 8)}…</td>
                  <td>
                    {execution.status}
                    {execution.status === "FAILED" && execution.failureReason && (
                      <div className="remediation-result remediation-result-error">
                        {execution.failureReason}
                      </div>
                    )}
                    {execution.status === "ROLLED_BACK" && execution.rollbackReason && (
                      <div className="remediation-result">{execution.rollbackReason}</div>
                    )}
                  </td>
                  <td>{execution.dryRun ? "Yes" : "No"}</td>
                  <td>
                    {execution.policyDecision}
                    <div className="remediation-policy-reason">{execution.policyReason}</div>
                  </td>
                  <td>{execution.requestedBy}</td>
                  <td>
                    <time dateTime={execution.createdAt}>
                      {new Date(execution.createdAt).toLocaleString()}
                    </time>
                  </td>
                  <td>
                    <button type="button" onClick={() => viewSteps(execution)}>
                      View steps
                    </button>
                  </td>
                  {canReview && (
                    <td>
                      <div className="remediation-actions">
                        {execution.status === "PROPOSED" && (
                          <>
                            <button
                              type="button"
                              onClick={() => requestConfirmation(execution, "approve")}
                            >
                              Approve
                            </button>
                            <button
                              type="button"
                              onClick={() => requestConfirmation(execution, "reject")}
                            >
                              Reject
                            </button>
                          </>
                        )}
                        {(execution.status === "PROPOSED" ||
                          execution.status === "APPROVED" ||
                          execution.status === "SCHEDULED" ||
                          execution.status === "RUNNING") && (
                          <button
                            type="button"
                            onClick={() => requestConfirmation(execution, "cancel")}
                          >
                            Cancel
                          </button>
                        )}
                        {isAdmin &&
                          (execution.status === "RUNNING" || execution.status === "SCHEDULED") && (
                            <button
                              type="button"
                              className="danger"
                              onClick={() => requestConfirmation(execution, "emergency-stop")}
                            >
                              Emergency stop
                            </button>
                          )}
                      </div>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AsyncBoundary>

      {selectedSteps && (
        <div className="remediation-steps-panel" role="region" aria-label="Step detail">
          <h2>Steps</h2>
          <ol>
            {selectedSteps.steps.map((step) => (
              <li key={step.id}>
                {step.rollbackStep ? "[rollback] " : ""}
                {step.stepName} — {step.adapterType} — {step.status}
                {step.error && <div className="remediation-result-error">{step.error}</div>}
              </li>
            ))}
          </ol>
          <button type="button" onClick={() => setSelectedSteps(null)}>
            Close
          </button>
        </div>
      )}

      <ConfirmDialog
        open={pending !== null}
        title={
          pending?.action === "approve"
            ? "Approve this remediation?"
            : pending?.action === "reject"
              ? "Reject this remediation?"
              : pending?.action === "cancel"
                ? "Cancel this remediation?"
                : "Trigger an emergency stop?"
        }
        description={
          pending
            ? `${pending.action} for execution ${pending.execution.id}. This cannot be undone.`
            : ""
        }
        confirmLabel={
          pending?.action === "approve"
            ? "Approve"
            : pending?.action === "reject"
              ? "Reject"
              : pending?.action === "cancel"
                ? "Cancel remediation"
                : "Emergency stop"
        }
        danger={pending?.action === "emergency-stop" || pending?.action === "cancel"}
        pending={submitting}
        onConfirm={confirm}
        onCancel={() => setPending(null)}
      />

      {proposeOpen && (
        <div className="remediation-propose-panel" role="region" aria-label="Propose remediation">
          <h2>Propose remediation</h2>
          {proposeError && (
            <p role="alert" className="action-error">
              {proposeError}
            </p>
          )}
          <AsyncBoundary
            state={runbooksState}
            onRetry={runbooksState.refetch}
            loadingLabel="Loading runbooks…"
            emptyCheck={(items) => items.length === 0}
            emptyLabel="No runbooks available."
          >
            {(runbooks) => (
              <>
                <label htmlFor="runbook-select">Runbook</label>
                <select
                  id="runbook-select"
                  value={selectedRunbookSlug}
                  onChange={(e) => setSelectedRunbookSlug(e.target.value)}
                >
                  <option value="">Select a runbook…</option>
                  {runbooks.map((runbook) => (
                    <option key={runbook.slug} value={runbook.slug}>
                      {runbook.title} ({runbook.riskClassification})
                    </option>
                  ))}
                </select>
              </>
            )}
          </AsyncBoundary>
          <label>
            <input type="checkbox" checked={dryRun} onChange={(e) => setDryRun(e.target.checked)} />
            Dry run (simulate only, never mutates state)
          </label>
          <div>
            <button type="button" onClick={submitPropose} disabled={!selectedRunbookSlug}>
              Submit
            </button>
            <button type="button" onClick={() => setProposeOpen(false)}>
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
