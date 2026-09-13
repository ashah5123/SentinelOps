# 0007. Transactional Outbox Pattern

- Status: Accepted
- Date: 2026-09-12

## Context

The incident service needs to both persist domain changes (e.g. a new incident) and publish a
corresponding event (`incident.detected.v1`) to Redpanda. A naive implementation — write to
PostgreSQL, then separately call the Kafka producer — has an unavoidable failure window: the
database commit can succeed while the broker publish fails (or vice versa), producing a
persisted incident with no corresponding event, or an event with no committed incident.
Two-phase commit across PostgreSQL and Kafka is not practical to operate, and this project's
local-first principle ([ADR 0003](0003-local-first-infrastructure.md)) rules out adding a
separate distributed-transaction coordinator.

## Decision

The incident service writes every outgoing event as a row in an `outbox_events` table, in the
exact same database transaction as the domain change it describes. A background publisher
polls for `PENDING` rows and publishes them to Redpanda, marking each `PUBLISHED` only after a
successful broker acknowledgment.

Concurrency safety uses PostgreSQL's `SELECT ... FOR UPDATE SKIP LOCKED` to claim a batch of
rows: multiple service instances running the publisher concurrently will never claim the same
row, so no additional distributed lock service is required.

## Consequences

- A domain change and its event are atomic with respect to the database: either both commit or
  neither does. There is no code path that persists an incident without eventually publishing
  its event, or publishes an event for an incident that was never actually committed.
- Publication is asynchronous and at-least-once, not synchronous or exactly-once: if the
  service crashes between a successful broker send and the local commit that marks the row
  `PUBLISHED`, the row is republished on restart. Consumers of `incident.detected.v1` and
  `audit.event.v1` must be idempotent — see [ADR 0008](0008-at-least-once-idempotent-consumers.md).
- A row that exhausts its configured retry attempts is marked `FAILED` and left in the
  `outbox_events` table rather than silently dropped, so it remains inspectable (see
  `services/incident-service/README.md` for the inspection query) without requiring a
  Kafka-side dead-letter topic for outbound publication failures — the message never reached
  the broker, so there is nothing for a broker-side DLQ to hold.
- The outbox introduces publish latency bounded by the configurable polling interval, not
  request latency: incident-creation API calls do not wait on Kafka availability.
- Retention/cleanup only ever removes `PUBLISHED` rows older than a configured window, and
  never touches `PENDING` or `FAILED` rows, so recent publication history remains available
  for troubleshooting.
