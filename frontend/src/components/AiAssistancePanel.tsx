import { useState } from "react";
import { generateAiSuggestion, listAiSuggestions, reviewAiSuggestion } from "../api/ai";
import { ApiError } from "../api/client";
import type { AiSuggestion, AiTriageResult, IncidentSeverity } from "../api/types";
import { useApiClient } from "../api/ApiClientProvider";
import { useAsyncData } from "../hooks/useAsyncData";
import { useAnnounce } from "./Announcer";
import { SeverityBadge } from "./SeverityBadge";

/**
 * AI-assisted triage panel (Phase 11). Purely additive to the normal incident workflow: it never
 * blocks or replaces the lifecycle actions above it, and generating/reviewing a suggestion never
 * mutates the incident on its own — accepting a field goes through the same authorized command
 * path the rest of this page uses (see api/ai.ts's reviewAiSuggestion, which the backend maps to
 * IncidentCommandService.changeSeverity — never a direct write). See
 * docs/development/ai-triage.md for the full trust-boundary explanation this panel reflects in
 * its copy (e.g. "requires human review", never "AI decided").
 */
export function AiAssistancePanel({
  incidentId,
  canRequest,
  onIncidentChanged,
}: {
  incidentId: string;
  canRequest: boolean;
  onIncidentChanged: () => void;
}) {
  const client = useApiClient();
  const announce = useAnnounce();
  const [generating, setGenerating] = useState(false);
  const [generateError, setGenerateError] = useState<string | null>(null);
  const [expandedEvidence, setExpandedEvidence] = useState<Record<string, boolean>>({});
  const [feedbackDrafts, setFeedbackDrafts] = useState<Record<string, string>>({});
  const [reviewingId, setReviewingId] = useState<string | null>(null);

  const suggestionsState = useAsyncData(
    (signal) => listAiSuggestions(client, incidentId, signal),
    [client, incidentId],
  );

  async function handleGenerate() {
    if (generating) return;
    setGenerating(true);
    setGenerateError(null);
    try {
      await generateAiSuggestion(client, incidentId);
      announce(
        "AI triage suggestion generated. It requires your review before anything is applied.",
      );
      suggestionsState.refetch();
    } catch (err) {
      if (err instanceof ApiError && err.status === 429) {
        setGenerateError(
          "Another request was made too recently for this incident — please wait a moment.",
        );
      } else {
        setGenerateError(
          err instanceof ApiError ? err.message : "Could not generate a suggestion.",
        );
      }
    } finally {
      setGenerating(false);
    }
  }

  async function handleReview(suggestion: AiSuggestion, acceptedFields: string[]) {
    setReviewingId(suggestion.id);
    try {
      await reviewAiSuggestion(client, incidentId, suggestion.id, {
        acceptedFields,
        feedback: feedbackDrafts[suggestion.id]?.trim() || undefined,
      });
      announce(
        acceptedFields.length === 0
          ? "Suggestion rejected."
          : `Suggestion reviewed; ${acceptedFields.join(", ")} accepted.`,
      );
      suggestionsState.refetch();
      if (acceptedFields.includes("severity")) {
        onIncidentChanged();
      }
    } catch {
      announce("Could not record your review. Please try again.");
    } finally {
      setReviewingId(null);
    }
  }

  return (
    <section aria-labelledby="ai-assistance-heading" className="ai-assistance-panel">
      <h2 id="ai-assistance-heading">AI Assistance</h2>
      <p className="ai-assistance-disclaimer">
        Suggestions are generated to help you investigate — they never change this incident by
        themselves and always require your review.
      </p>

      {canRequest && (
        <div className="ai-assistance-actions">
          <button type="button" disabled={generating} onClick={handleGenerate}>
            {generating ? "Generating…" : "Generate AI triage suggestion"}
          </button>
          {generateError && (
            <p role="alert" className="action-error">
              {generateError}
            </p>
          )}
        </div>
      )}

      {suggestionsState.status === "loading" && suggestionsState.data === null && (
        <p>Loading AI suggestions…</p>
      )}
      {suggestionsState.status === "error" && (
        <p role="alert" className="action-error">
          {suggestionsState.error}
        </p>
      )}
      {suggestionsState.data && suggestionsState.data.length === 0 && (
        <p>No AI suggestions have been requested for this incident yet.</p>
      )}

      {suggestionsState.data?.map((suggestion) => (
        <AiSuggestionCard
          key={suggestion.id}
          suggestion={suggestion}
          canReview={canRequest}
          reviewing={reviewingId === suggestion.id}
          expanded={!!expandedEvidence[suggestion.id]}
          onToggleExpanded={() =>
            setExpandedEvidence((prev) => ({ ...prev, [suggestion.id]: !prev[suggestion.id] }))
          }
          feedback={feedbackDrafts[suggestion.id] ?? ""}
          onFeedbackChange={(value) =>
            setFeedbackDrafts((prev) => ({ ...prev, [suggestion.id]: value }))
          }
          onReview={(fields) => handleReview(suggestion, fields)}
        />
      ))}
    </section>
  );
}

function parseResult(suggestion: AiSuggestion): AiTriageResult | null {
  if (suggestion.status !== "COMPLETED" || !suggestion.structuredResult) return null;
  try {
    return JSON.parse(suggestion.structuredResult) as AiTriageResult;
  } catch {
    return null;
  }
}

function AiSuggestionCard({
  suggestion,
  canReview,
  reviewing,
  expanded,
  onToggleExpanded,
  feedback,
  onFeedbackChange,
  onReview,
}: {
  suggestion: AiSuggestion;
  canReview: boolean;
  reviewing: boolean;
  expanded: boolean;
  onToggleExpanded: () => void;
  feedback: string;
  onFeedbackChange: (value: string) => void;
  onReview: (acceptedFields: string[]) => void;
}) {
  const result = parseResult(suggestion);
  const pending = suggestion.reviewStatus === "PENDING";

  return (
    <article
      className="ai-suggestion-card"
      aria-label={`AI suggestion from ${suggestion.createdAt}`}
    >
      <header>
        <span className="ai-suggestion-model">
          {suggestion.providerName} ({suggestion.modelName})
        </span>
        <time dateTime={suggestion.createdAt}>
          {new Date(suggestion.createdAt).toLocaleString()}
        </time>
      </header>

      {suggestion.status === "MODEL_UNAVAILABLE" && (
        <p className="ai-suggestion-unavailable">
          AI assistance was temporarily unavailable: {suggestion.failureReason}
        </p>
      )}
      {suggestion.status === "FAILED" && (
        <p className="ai-suggestion-failed">
          The suggestion could not be produced: {suggestion.failureReason}
        </p>
      )}

      {result && (
        <div className="ai-suggestion-result">
          <p className="ai-suggestion-summary">{result.summary}</p>
          <dl>
            <div>
              <dt>Suggested category</dt>
              <dd>{result.suggestedCategory}</dd>
            </div>
            <div>
              <dt>Suggested severity</dt>
              <dd>
                <SeverityBadge severity={result.suggestedSeverity as IncidentSeverity} />
              </dd>
            </div>
            <div>
              <dt>Confidence</dt>
              <dd>{result.confidenceStatement}</dd>
            </div>
          </dl>

          <h3>Suggested diagnostic steps</h3>
          <ul>
            {result.diagnosticSteps.map((step, i) => (
              <li key={i}>{step}</li>
            ))}
          </ul>

          {result.escalationConditions.length > 0 && (
            <>
              <h3>Escalation conditions</h3>
              <ul>
                {result.escalationConditions.map((c, i) => (
                  <li key={i}>{c}</li>
                ))}
              </ul>
            </>
          )}

          <button type="button" onClick={onToggleExpanded} aria-expanded={expanded}>
            {expanded ? "Hide evidence and citations" : "Show evidence and citations"}
          </button>

          {expanded && (
            <div className="ai-suggestion-evidence">
              <h3>Evidence</h3>
              <ul>
                {result.evidence.map((e, i) => (
                  <li key={i}>{e}</li>
                ))}
              </ul>

              <h3>Citations</h3>
              {result.citations.length === 0 ? (
                <p>No runbook passage was cited for this suggestion.</p>
              ) : (
                <ul>
                  {result.citations.map((c) => (
                    <li key={c.chunkId}>
                      {c.sourceTitle} — {c.section} (relevance {c.score.toFixed(2)})
                    </li>
                  ))}
                </ul>
              )}

              <h3>Limitations</h3>
              <ul>
                {result.limitations.map((l, i) => (
                  <li key={i}>{l}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}

      {result && canReview && (
        <div className="ai-suggestion-review">
          {pending ? (
            <>
              <p role="note" className="ai-suggestion-review-banner">
                This suggestion requires human review before anything is applied.
              </p>
              <label htmlFor={`feedback-${suggestion.id}`}>Feedback (optional)</label>
              <input
                id={`feedback-${suggestion.id}`}
                type="text"
                value={feedback}
                onChange={(e) => onFeedbackChange(e.target.value)}
                placeholder="Why did you accept or reject this?"
              />
              <div className="ai-suggestion-review-actions">
                <button type="button" disabled={reviewing} onClick={() => onReview(["severity"])}>
                  Accept suggested severity
                </button>
                <button type="button" disabled={reviewing} onClick={() => onReview(["category"])}>
                  Accept suggested category
                </button>
                <button type="button" disabled={reviewing} onClick={() => onReview([])}>
                  Reject
                </button>
              </div>
            </>
          ) : (
            <p className="ai-suggestion-reviewed">
              Reviewed by {suggestion.reviewedBy} ({suggestion.reviewStatus.toLowerCase()}
              {suggestion.reviewedAt
                ? ` at ${new Date(suggestion.reviewedAt).toLocaleString()}`
                : ""}
              ){suggestion.reviewFeedback ? `: "${suggestion.reviewFeedback}"` : ""}
            </p>
          )}
        </div>
      )}
    </article>
  );
}
