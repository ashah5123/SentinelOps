---
slug: failed-event-delivery
title: Failed Event Delivery (Dead-Lettered Events)
version: 1
owner: platform-team
last_reviewed: 2026-01-15
services: incident-service, telemetry-correlation-service, redpanda
signals: sentinelops_consumer_dead_lettered_total, sentinelops_outbox_dead_lettered_total, .dlq topics
---

## Symptoms

An event permanently fails processing and is routed to its topic's dead-letter (`.dlq`) topic —
either because it exhausted its retry budget, or because it was malformed/invalid and skipped
retries entirely (see [ADR 0011](../decisions/0011-reliability-and-failure-recovery.md)). This
shows up as `sentinelops_consumer_dead_lettered_total` or `sentinelops_outbox_dead_lettered_total`
incrementing, or an outbox row in the `FAILED` status.

## Safe diagnostic steps

1. Identify which `.dlq` topic received the event and inspect it (see
   `docs/development/reliability.md`'s "Inspecting and replaying dead-lettered events" section for
   the exact `rpk topic consume` command).
2. Determine whether the failure was due to malformed data (a genuine bug in the producer or a
   schema mismatch) or a transient dependency outage that outlasted the retry budget.
3. For a FAILED outbox row, inspect `incidents.outbox_events.last_error` for the recorded failure
   reason (see the SQL query in `services/incident-service/README.md`'s "Inspecting failed outbox
   events" section).
4. Confirm whether the underlying cause (e.g. a database or broker outage) has already been
   resolved before considering replay.

## Escalation conditions

- The dead-lettered event represents a real incident or evidence record that has not been created
  as a result, and no equivalent information exists elsewhere.
- The volume of dead-lettered events is large enough to suggest a systemic issue rather than an
  isolated bad event.
- The root cause is a genuine data/schema bug (not a transient outage), requiring a code fix
  before replay can succeed.

## Recovery considerations

- Only an ADMIN can replay eligible dead-lettered events, through the existing recovery mechanism
  (`POST /api/v1/admin/dead-letter-topics/{topic}/replay` — see
  `docs/development/security.md`'s role-permission matrix), never by hand-editing the topic.
- Replayed events keep their original event ID, so the target topic's existing idempotent-
  consumption check still applies — replaying an event that actually did succeed before being
  incorrectly dead-lettered is a safe no-op, not a duplicate.
- Do not replay an event whose root cause (a data/schema bug) has not been fixed — it will
  simply be dead-lettered again, per `docs/development/reliability.md`.

## Verification steps

1. After replay, confirm the expected incident/evidence record now exists.
2. Confirm the audit trail records the `DEAD_LETTER_REPLAYED` action (ADMIN-only,
   `GET /api/v1/admin/audit-events?action=DEAD_LETTER_REPLAYED`).
3. Confirm the replayed event does not reappear on the same dead-letter topic.
