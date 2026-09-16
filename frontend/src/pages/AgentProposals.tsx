import { useState } from "react";
import { approveProposal, listRecentProposals, rejectProposal } from "../api/proposals";
import { useApiClient } from "../api/ApiClientProvider";
import { useAsyncData } from "../hooks/useAsyncData";
import { AsyncBoundary } from "../components/AsyncBoundary";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { useAnnounce } from "../components/Announcer";
import { useAuth } from "../auth/AuthProvider";
import { hasAnyRole } from "../lib/roles";
import { ApiError } from "../api/client";
import type { AgentProposal } from "../api/types";

const HIGH_IMPACT_ACTIONS = new Set(["RESOLVE", "REPLAY_DEAD_LETTER", "CHANGE_SEVERITY"]);

/**
 * Agent Proposals (Phase 13, section 9). Every proposal here was either created by an MCP tool
 * call or by a responder through the console — approving one calls the backend's
 * approve-and-execute endpoint, which re-verifies authorization, expiration, incident version,
 * and content integrity server-side before anything changes. This page cannot bypass any of
 * that: it only ever calls the same REST endpoint a curl request would.
 */
export function AgentProposals() {
  const client = useApiClient();
  const auth = useAuth();
  const announce = useAnnounce();
  const canReview = hasAnyRole(auth.roles, ["RESPONDER", "ADMIN"]);

  const [pending, setPending] = useState<{
    proposal: AgentProposal;
    action: "approve" | "reject";
  } | null>(null);
  const [reviewNote, setReviewNote] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);

  const proposalsState = useAsyncData((signal) => listRecentProposals(client, signal), [client]);

  function requestConfirmation(proposal: AgentProposal, action: "approve" | "reject") {
    setReviewNote("");
    setActionError(null);
    setPending({ proposal, action });
  }

  async function confirm() {
    if (!pending || submitting) return;
    setSubmitting(true);
    setActionError(null);
    try {
      if (pending.action === "approve") {
        await approveProposal(client, pending.proposal.id, reviewNote || undefined);
        announce(
          `Proposal ${pending.action === "approve" ? "approved and executed" : "rejected"}.`,
        );
      } else {
        await rejectProposal(client, pending.proposal.id, reviewNote || undefined);
        announce("Proposal rejected.");
      }
      proposalsState.refetch();
      setPending(null);
    } catch (err) {
      setActionError(err instanceof ApiError ? err.message : "The request could not be completed.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="agent-proposals">
      <h1>Agent Proposals</h1>
      <p className="agent-proposals-disclaimer">
        Every proposal below requires human approval before anything changes. Approving executes it
        immediately through the normal incident command path; rejecting discards it permanently.
      </p>

      {actionError && (
        <p role="alert" className="action-error">
          {actionError}
        </p>
      )}

      <AsyncBoundary
        state={proposalsState}
        onRetry={proposalsState.refetch}
        loadingLabel="Loading proposals…"
        emptyCheck={(items) => items.length === 0}
        emptyLabel="No agent proposals yet."
      >
        {(proposals) => (
          <table className="agent-proposals-table">
            <thead>
              <tr>
                <th>Incident</th>
                <th>Action</th>
                <th>Risk</th>
                <th>Requested by</th>
                <th>Reason</th>
                <th>Status</th>
                <th>Created</th>
                <th>Expires</th>
                {canReview && <th>Review</th>}
              </tr>
            </thead>
            <tbody>
              {proposals.map((proposal) => (
                <tr key={proposal.id}>
                  <td>
                    <a href={`/incidents/${proposal.incidentId}`}>
                      {proposal.incidentId.slice(0, 8)}…
                    </a>
                  </td>
                  <td>{proposal.actionType}</td>
                  <td>{proposal.riskClassification}</td>
                  <td>{proposal.requestedBy}</td>
                  <td>{proposal.reason}</td>
                  <td>
                    {proposal.status}
                    {proposal.status === "EXECUTED" && proposal.executionResult && (
                      <div className="proposal-result">{proposal.executionResult}</div>
                    )}
                    {proposal.status === "EXECUTION_FAILED" && proposal.executionError && (
                      <div className="proposal-result proposal-result-error">
                        {proposal.executionError}
                      </div>
                    )}
                  </td>
                  <td>
                    <time dateTime={proposal.createdAt}>
                      {new Date(proposal.createdAt).toLocaleString()}
                    </time>
                  </td>
                  <td>
                    <time dateTime={proposal.expiresAt}>
                      {new Date(proposal.expiresAt).toLocaleString()}
                    </time>
                  </td>
                  {canReview && (
                    <td>
                      {proposal.status === "PENDING" ? (
                        <div className="proposal-review-actions">
                          <button
                            type="button"
                            onClick={() => requestConfirmation(proposal, "approve")}
                          >
                            Approve
                          </button>
                          <button
                            type="button"
                            onClick={() => requestConfirmation(proposal, "reject")}
                          >
                            Reject
                          </button>
                        </div>
                      ) : (
                        <span className="proposal-reviewed">
                          {proposal.approvedBy && `Approved by ${proposal.approvedBy}`}
                          {proposal.rejectedBy && `Rejected by ${proposal.rejectedBy}`}
                        </span>
                      )}
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </AsyncBoundary>

      <ConfirmDialog
        open={pending !== null}
        title={
          pending?.action === "approve"
            ? "Approve and execute this proposal?"
            : "Reject this proposal?"
        }
        description={
          pending
            ? `${pending.action === "approve" ? "Approving" : "Rejecting"} the ${pending.proposal.actionType} proposal on incident ${pending.proposal.incidentId}. ${
                pending.action === "approve"
                  ? "This executes immediately and cannot be undone."
                  : "This cannot be undone."
              }`
            : ""
        }
        confirmLabel={pending?.action === "approve" ? "Approve and execute" : "Reject"}
        danger={
          pending?.action === "approve" && HIGH_IMPACT_ACTIONS.has(pending.proposal.actionType)
        }
        pending={submitting}
        onConfirm={confirm}
        onCancel={() => setPending(null)}
      />

      {pending && (
        <div className="proposal-review-note">
          <label htmlFor="review-note">Review note (optional)</label>
          <input
            id="review-note"
            type="text"
            value={reviewNote}
            onChange={(e) => setReviewNote(e.target.value)}
          />
        </div>
      )}
    </div>
  );
}
