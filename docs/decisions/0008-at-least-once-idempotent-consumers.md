# 0008. At-Least-Once Delivery and Idempotent Consumers

- Status: Accepted
- Date: 2026-09-12

## Context

Both the outbox publisher ([ADR 0007](0007-transactional-outbox-pattern.md)) and the incident
service's own Kafka consumer can redeliver a message: the publisher may republish a row that
was actually already sent, and a consumer offset is only committed after successful processing,
so a crash or rebalance between processing and offset-commit causes the same message to be
redelivered. Kafka-compatible brokers (Redpanda included) do not provide exactly-once
processing guarantees across an arbitrary consumer's side effects (here: creating a database
row) without significant additional machinery. Claiming exactly-once semantics this project
does not actually implement would be misleading and would let a real duplicate-processing bug
hide behind an incorrect assumption.

## Decision

SentinelOps services treat all Kafka-compatible event delivery — both inbound and outbound —
as at-least-once, never exactly-once, and document this explicitly rather than implying
stronger guarantees. Every consumer that causes a side effect must be idempotent with respect
to redelivery of the same logical event.

For the incident service's anomaly consumer, idempotency is enforced primarily by a database
uniqueness constraint on `incidents.source_event_id` (the authoritative guard) backed by a
`processed_events` table recording event IDs this consumer has handled (a fast-path check that
avoids hitting the constraint on the common case). A message that cannot be processed
successfully after bounded retries is published to its topic's dead-letter topic (e.g.
`telemetry.anomaly.v1.dlq`) rather than retried forever, so a single poison message cannot
block the partition.

## Consequences

- Every future consumer of a SentinelOps event must identify a natural idempotency key (here,
  the envelope's `eventId`) and use it — either via a database constraint, an upsert, or an
  equivalent mechanism — rather than assuming a message is only ever delivered once.
- Duplicate delivery is expected, ordinary behavior, not an error condition. Logs reflect this
  (a duplicate is logged as an informational no-op, not a warning or error).
- The dead-letter topics are a terminal state requiring human investigation; nothing in this
  phase automatically reprocesses a dead-lettered message. Operators use Redpanda Console (see
  `docs/development/local-platform.md`) to inspect dead-lettered messages.
- This decision applies uniformly across the platform: any future service consuming
  `incident.detected.v1`, `audit.event.v1`, or any other SentinelOps event must be built to the
  same at-least-once, idempotent-consumer contract — it is a platform-wide invariant, not an
  incident-service-specific detail.
