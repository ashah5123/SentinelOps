import { useState } from "react";
import {
  ELIGIBLE_DEAD_LETTER_TOPICS,
  replayDeadLetterTopic,
  searchAuditEvents,
} from "../api/admin";
import { useApiClient } from "../api/ApiClientProvider";
import { useAsyncData } from "../hooks/useAsyncData";
import { AsyncBoundary } from "../components/AsyncBoundary";
import { ConfirmDialog } from "../components/ConfirmDialog";
import { useAnnounce } from "../components/Announcer";
import type { DeadLetterReplayResult } from "../api/types";
import { ApiError } from "../api/client";

/**
 * Administrative recovery (Phase 10). Scoped to what the backend actually exposes:
 * `DeadLetterReplayService` replays a bounded batch from an eligible `.dlq` topic and returns a
 * replayed/failed count (see docs/api/incident-service.md) — there is no endpoint that lists
 * individual dead-lettered event records (failure category, attempt count, first/last failure
 * timestamp, or a link to a specific related incident), because no such per-event metadata is
 * persisted anywhere; a dead-lettered Kafka record is not a queryable database row. Building that
 * would be a new, nontrivial backend feature (introspecting Kafka topics with metadata tracking),
 * not "the smallest secure addition," so it was deliberately not added — this view is honest about
 * that gap rather than fabricating per-event data. Every replay is still fully audited (see the
 * audit trail below, populated by the same `DEAD_LETTER_REPLAYED` action the backend records).
 */
export function AdminRecovery() {
  const client = useApiClient();
  const announce = useAnnounce();
  const [pendingTopic, setPendingTopic] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [results, setResults] = useState<Record<string, DeadLetterReplayResult | string>>({});

  const auditState = useAsyncData(
    (signal) => searchAuditEvents(client, { action: "DEAD_LETTER_REPLAYED", size: 10 }, signal),
    [client],
  );

  async function confirmReplay(topic: string) {
    if (submitting) return;
    setSubmitting(true);
    try {
      const result = await replayDeadLetterTopic(client, topic, 20);
      setResults((prev) => ({ ...prev, [topic]: result }));
      announce(
        `Replay of ${topic} complete: ${result.replayedCount} replayed, ${result.failedCount} failed.`,
      );
      auditState.refetch();
    } catch (err) {
      const message = err instanceof ApiError ? err.message : "Replay failed.";
      setResults((prev) => ({ ...prev, [topic]: message }));
      announce(`Replay of ${topic} failed: ${message}`);
    } finally {
      setSubmitting(false);
      setPendingTopic(null);
    }
  }

  return (
    <div className="admin-recovery">
      <h1>Administrative recovery</h1>
      <p>
        Replays eligible dead-lettered events back to their original topic, in bounded batches, via
        the existing recovery mechanism (see docs/development/reliability.md). This does not expose
        credentials, tokens, or raw payloads — only counts.
      </p>

      <table className="dlq-table">
        <caption className="visually-hidden">Eligible dead-letter topics</caption>
        <thead>
          <tr>
            <th scope="col">Dead-letter topic</th>
            <th scope="col">Last replay result</th>
            <th scope="col">Action</th>
          </tr>
        </thead>
        <tbody>
          {ELIGIBLE_DEAD_LETTER_TOPICS.map((topic) => {
            const result = results[topic];
            return (
              <tr key={topic}>
                <th scope="row">
                  <code>{topic}</code>
                </th>
                <td>
                  {!result && "Not replayed this session"}
                  {result && typeof result === "string" && <span role="alert">{result}</span>}
                  {result && typeof result === "object" && (
                    <span>
                      {result.replayedCount} replayed, {result.failedCount} failed (target:{" "}
                      {result.targetTopic})
                    </span>
                  )}
                </td>
                <td>
                  <button
                    type="button"
                    disabled={submitting}
                    onClick={() => setPendingTopic(topic)}
                  >
                    Replay eligible events
                  </button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>

      <ConfirmDialog
        open={pendingTopic !== null}
        title="Confirm dead-letter replay"
        description={`This will republish up to 20 eligible records from ${pendingTopic} back to its original topic. Replayed events keep their original event ID, so idempotent consumption still applies. This action is audited.`}
        confirmLabel="Replay"
        danger
        pending={submitting}
        onConfirm={() => pendingTopic && confirmReplay(pendingTopic)}
        onCancel={() => setPendingTopic(null)}
      />

      <section aria-labelledby="replay-audit-heading">
        <h2 id="replay-audit-heading">Recent replay actions (audit trail)</h2>
        <AsyncBoundary
          state={auditState}
          onRetry={auditState.refetch}
          loadingLabel="Loading audit history…"
          emptyCheck={(page) => page.content.length === 0}
          emptyLabel="No replay actions recorded yet."
        >
          {(page) => (
            <ul className="audit-list">
              {page.content.map((event) => (
                <li key={event.id}>
                  <time dateTime={event.occurredAt}>
                    {new Date(event.occurredAt).toLocaleString()}
                  </time>{" "}
                  — {event.action} by {event.actorId}
                </li>
              ))}
            </ul>
          )}
        </AsyncBoundary>
      </section>
    </div>
  );
}
