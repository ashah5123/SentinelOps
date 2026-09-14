# SentinelOps Reliability and Failure Recovery (Phase 6)

Status: Phase 6. Describes the reliability guarantees the incident service and
telemetry-correlation service provide, how to exercise their failure-handling paths locally, and
how to diagnose the failure conditions their new metrics and alerts (see
`docs/development/observability.md`) are built to surface. See
[ADR 0007](../decisions/0007-transactional-outbox-pattern.md),
[ADR 0008](../decisions/0008-at-least-once-idempotent-consumers.md), and
[ADR 0011](../decisions/0011-reliability-and-failure-recovery.md) for the design rationale.

## Reliability guarantees

- **At-least-once delivery, everywhere.** Every event this platform publishes or consumes may be
  delivered more than once — after a crash between a Kafka send succeeding and the outbox row
  being marked published, after a consumer retry, or after a manual dead-letter replay. Nothing
  in this system provides exactly-once delivery, and nothing should be built assuming it does.
- **Why idempotency is required.** Because delivery is at-least-once, every consumer must produce
  the same *effect* whether it processes an event once or ten times. Every Kafka listener in both
  services keys its idempotency check on the event envelope's `eventId` — either via a shared
  `processed_events` table with a unique constraint (the incident service's two event-triggered
  flows), or via a unique constraint on the target domain table itself (the
  telemetry-correlation service's deployment/dependency/correlation consumers). Duplicate
  detection and the business-effect write happen inside the same database transaction, so there
  is no window where a duplicate could slip through between the check and the write.
- **Transactional outbox.** A domain change and the event describing it are written in the same
  database transaction (ADR 0007). A background publisher claims pending rows, sends them to
  Kafka, and marks them published — see "Outbox internals" below for exactly how that claim/send/
  finalize sequence avoids holding a transaction open across the network call.
- **Bounded retry, not infinite retry.** Every retry loop (outbox publish, Kafka consumption) is
  bounded by a configured attempt limit. A failure that keeps recurring past that limit is moved
  to a terminal, inspectable state (`FAILED` for outbox rows, the topic's `.dlq` topic for
  consumed records) rather than retried forever.
- **Non-retryable failures skip the retry budget entirely.** Malformed JSON and invalid
  validation values (e.g. an unrecognized severity string) are classified as permanent and go
  straight to the dead-letter topic — see ADR 0011.

## Outbox internals

`OutboxPublisher.publishDueEvents()` runs three separate, short operations per polling cycle
rather than one long transaction:

1. **Claim** (`OutboxTransactions.claimBatch`, one short transaction): `SELECT ... FOR UPDATE SKIP
   LOCKED` picks up to `batch-size` `PENDING` rows due for publication, then immediately "leases"
   each one by moving its `next_attempt_at` forward by `lease-duration` — this commits quickly,
   releasing the row lock, while the lease itself (not a database lock) protects the row from a
   second instance re-claiming it.
2. **Send** (no open transaction): the actual Kafka send, bounded by `publish-timeout`.
3. **Finalize** (one short transaction per row): `markPublished` on success, or `recordFailure`
   (which computes the next jittered backoff and marks the row `FAILED` once `max-attempts` is
   exhausted) on failure.

If the process crashes between steps 1 and 3, the row's lease simply expires and it becomes
claimable again on a later cycle — this is the same at-least-once, redelivery-safe behavior the
outbox has always required of its consumers (ADR 0008), it just no longer requires holding a
database transaction open for the duration of a network call.

### Configuration (env vars — see `.env.example`)

| Variable | Default | Meaning |
|---|---|---|
| `OUTBOX_POLLING_INTERVAL` | `2s` | How often the publisher looks for due rows. |
| `OUTBOX_BATCH_SIZE` | `50` | Max rows claimed per cycle. |
| `OUTBOX_MAX_ATTEMPTS` | `8` | Attempts before a row is marked `FAILED`. |
| `OUTBOX_INITIAL_BACKOFF` / `OUTBOX_MAX_BACKOFF` | `1s` / `60s` | Exponential backoff bounds between attempts. |
| `OUTBOX_LEASE_DURATION` | `30s` | How long a claimed row is protected from re-claiming; must exceed `OUTBOX_PUBLISH_TIMEOUT`. |
| `OUTBOX_PUBLISH_TIMEOUT` | `10s` | Max time to wait for one Kafka send. |
| `OUTBOX_BACKOFF_JITTER` | `0.2` | Fractional random jitter on the backoff (`0.0` disables it). |
| `OUTBOX_RETENTION_AFTER_PUBLISH` | `24h` | How long `PUBLISHED` rows are kept before cleanup. |

## Retry and dead-letter behavior (inbound consumers)

Each `@KafkaListener` container is configured (see each service's `KafkaConfig`) with:

- **Bounded exponential backoff with jitter** (`JitteredExponentialBackOff`), configurable via
  `*_CONSUMER_RETRY_INITIAL_INTERVAL`, `*_CONSUMER_RETRY_MAX_INTERVAL`,
  `*_CONSUMER_RETRY_MULTIPLIER`, `*_CONSUMER_RETRY_JITTER`.
- **A non-retryable fast path** for `JsonProcessingException` and `IllegalArgumentException` —
  these go straight to the dead-letter topic without consuming any retry attempts.
- **A delivery-attempt header** (`setDeliveryAttemptHeader(true)`) so the number of attempts made
  travels with the record all the way to the dead-letter topic.
- **`AckMode.RECORD`**: the offset is only committed after the listener method — which wraps the
  full business transaction — returns normally. A thrown exception is never silently swallowed.

Once retries are exhausted (or a non-retryable exception is thrown immediately), Spring Kafka's
`DeadLetterPublishingRecoverer` publishes the original record — key, value (the full original
event envelope, including `eventId`), and headers, plus the exception's class/message as
additional headers — to the topic's `.dlq` topic, and `sentinelops[.telemetry].consumer.dead_lettered`
is incremented.

### Inspecting and replaying dead-lettered events

```bash
# List the DLQ topics for a running platform:
docker compose --env-file .env -f infrastructure/docker/docker-compose.yml \
  exec redpanda rpk topic list | grep '\.dlq$'

# Inspect the next few dead-lettered records on a topic (never destructive — this only reads):
docker compose --env-file .env -f infrastructure/docker/docker-compose.yml \
  exec redpanda rpk topic consume telemetry.anomaly.v1.dlq --num 10
```

Each record's value is the exact original event envelope JSON — including its `eventId` — so it
is safe to republish to the *original* topic once the underlying problem is fixed:

```bash
docker compose --env-file .env -f infrastructure/docker/docker-compose.yml \
  exec redpanda rpk topic consume telemetry.anomaly.v1.dlq --num 1 --format '%v' \
  | docker compose --env-file .env -f infrastructure/docker/docker-compose.yml \
    exec -T redpanda rpk topic produce telemetry.anomaly.v1
```

**Replayed events remain idempotent** because the replayed envelope carries the same `eventId` as
the original — every consumer's idempotency check (the whole point of ADR 0008) applies exactly as
it would to any other redelivery. If the original event had already been partially or fully
processed before landing on the DLQ (e.g. it failed on the *second* of two side effects), replay
is still safe: the idempotency check happens before any side effect is (re)applied. Never edit an
event's `eventId` when replaying — doing so defeats the very idempotency check that makes replay
safe.

### Outbox `FAILED` rows (as opposed to consumer dead-lettering)

An outbox row that exhausts its attempts is marked `FAILED` in place (not moved to a Kafka topic)
and kept for manual inspection:

```sql
SELECT id, topic, event_type, attempt_count, last_error, created_at
FROM incidents.outbox_events   -- or telemetry.outbox_events
WHERE status = 'FAILED'
ORDER BY created_at DESC;
```

To retry a `FAILED` row manually, reset it to `PENDING` and clear its attempt count so it is
picked up by the next polling cycle — this is safe for the same idempotency reasons as DLQ
replay, since the row's `id` (and therefore the event it was already assigned) doesn't change:

```sql
UPDATE incidents.outbox_events
SET status = 'PENDING', attempt_count = 0, next_attempt_at = now(), last_error = NULL
WHERE id = '<uuid>';
```

## Local failure-testing workflow

`infrastructure/docker/scripts/reliability-fault-test.sh` reproduces five scenarios against a
running `app`-profile platform (`make incident-up`), using only `docker compose pause/unpause/
restart` and `rpk` — no paid tooling, no external services:

```bash
make reliability-test SCENARIO=duplicate-delivery
make reliability-test SCENARIO=broker-outage
make reliability-test SCENARIO=app-restart
make reliability-test SCENARIO=invalid-event
make reliability-test SCENARIO=retry-exhaustion
make reliability-test                          # runs all five in sequence
```

Each scenario prints what it did and how to verify the outcome (an API query, a SQL query, or a
`rpk topic consume` command); none of them delete data or persistent volumes, and the script
always unpauses any container it paused, even on failure (via a shell `trap`). See
`docs/benchmarks/phase-6-reliability.md` for actual observations recorded from running these
scenarios.

## Dashboards and alerts

- Grafana dashboard **"SentinelOps Reliability"** (`sentinelops-reliability`,
  `infrastructure/docker/observability/grafana/dashboards/sentinelops-reliability.json`): outbox
  backlog and oldest-pending age (both services), publish success/failure rates, dead-lettered
  counts, consumer retry attempts, consumer processing latency (p95), duplicate-event rates, and
  circuit-breaker state.
- Prometheus alert group **`sentinelops-reliability`**
  (`infrastructure/docker/observability/prometheus/rules/sentinelops-alerts.yml`):
  `SentinelOps(Telemetry)OutboxBacklogGrowing`, `SentinelOps(Telemetry)OutboxOldestPendingTooOld`,
  `SentinelOps(Telemetry)ConsumerDeadLetterAccumulation`. Thresholds (100 pending rows / 5
  minutes old) are local-development defaults, not tuned to any production workload — see the
  full metric catalog in `docs/development/observability.md`.

## Operational runbook

### A growing outbox backlog (`SentinelOpsOutboxBacklogGrowing` / `...TelemetryOutboxBacklogGrowing`)

1. Check `sentinelops_outbox_oldest_pending_age_seconds` — is it also climbing? If so, nothing is
   publishing at all (see step 2); if it's flat while the count grows, publishing is keeping up
   with *old* work but new work is arriving faster than it can be sent.
2. Check broker reachability: `make infra-status` (or `docker compose ps redpanda`) and
   `/actuator/health/readiness` on the affected service — a `DOWN` `kafkaConnectivity` indicator
   points at the broker, not the application.
3. Check `sentinelops[.telemetry].outbox.published{outcome="failure"}` — climbing failures with a
   reachable broker suggests a topic/permission/message-size problem; check the publisher's logs
   for the actual exception in `last_error` on the affected rows.
4. Do not delete or truncate the outbox table. Once the underlying cause is fixed, the backlog
   drains on its own via the normal polling cycle.

### Repeated publication failures

1. `SELECT * FROM outbox_events WHERE status = 'FAILED' ORDER BY created_at DESC LIMIT 20;` — read
   `last_error` for the actual exception message (never logged with secrets — see "What is never
   logged" below).
2. If the error is broker-side (e.g. `NOT_ENOUGH_REPLICAS`, unreachable), fix the broker and
   manually reset the affected rows to `PENDING` (see "Outbox `FAILED` rows" above) once healthy.
3. If the error is payload-related (e.g. oversized message), the row will keep failing even after
   reset — this needs a code/data fix, not a retry.

### Consumer lag

Spring Kafka's listener-observation metrics (`spring_kafka_listener_seconds_count`, enabled via
`spring.kafka.listener.observation-enabled`) show processing rate and latency per listener, and
the Grafana "Consumer processing latency (p95)" panel surfaces this. This repository does not run
a dedicated Kafka consumer-group-lag exporter (adding one was judged out of scope for a local
default — see "Known limitations" below); to see actual partition lag directly, use the optional
Redpanda Console (`COMPOSE_PROFILES=console,app`, `http://127.0.0.1:8080` by default) or
`rpk group describe <group-id>` inside the `redpanda` container.

### Dead-letter accumulation (`SentinelOps(Telemetry)ConsumerDeadLetterAccumulation`)

1. Inspect the relevant `.dlq` topic (see "Inspecting and replaying dead-lettered events" above).
2. Classify each message: a burst right after a known outage is often a false alarm (the
   originating events were genuinely unprocessable at the time, e.g. hit `retry-exhaustion` during
   a long outage) — cross-check timing against `SentinelOpsIncidentServiceDown` /
   `kafkaConnectivity`/`db` health history first.
3. A steady trickle of dead letters unrelated to any outage usually means a non-retryable,
   permanent problem (bad data from an upstream producer, a schema mismatch) — fix the root cause
   before replaying, or replay attempts will simply be dead-lettered again.

### PostgreSQL or Redpanda unavailability

1. `/actuator/health/readiness` on both services now fails (503) when either dependency is down
   (ADR 0011) — this is the fastest way to confirm impact and scope.
2. `make infra-status` shows container-level health.
3. Once the dependency recovers, both services reconnect automatically — HikariCP re-establishes
   database connections, and the Kafka client reconnects to the broker — no restart is required.
   Confirm recovery via readiness returning to 200 and the outbox backlog/oldest-pending-age
   gauges falling back toward zero.

## What is never logged

Structured logs (see `docs/development/observability.md`) include the service name, environment,
trace ID, span ID, correlation ID, and a stable error code where applicable — but never request
bodies, credentials, tokens, or full event payloads. `OutboxEvent.last_error` and DLQ exception
headers carry the exception's message only, not the payload itself.

## Known limitations

- Single-instance backpressure/leasing only — the outbox lease and the ingestion scheduler's
  single-flight guard (Phase 5) are both in-process (`AtomicBoolean`/database-lease) mechanisms
  sufficient for this project's current single-instance local deployment. A multi-instance
  deployment would still work correctly (the lease and `FOR UPDATE SKIP LOCKED` both remain
  correct under concurrency) but has not been load-tested under multiple concurrent publisher
  instances.
- No dedicated Kafka consumer-lag exporter is included by default (see "Consumer lag" above).
- "Recovery time after a dependency becomes available again" is exposed as a proxy metric
  (`oldest_pending_age_seconds` falling back to zero) rather than a dedicated
  outage-duration/recovery-time histogram — implementing the latter correctly requires tracking
  outage start/end timestamps per dependency, which was judged unnecessary complexity for this
  phase given the proxy metric already answers the operational question ("how long until things
  caught up").
- The reliability guarantees described here apply to the incident service and
  telemetry-correlation service only — no other service in this repository consumes or produces
  Kafka events yet.
- Runtime verification of everything in this document is blocked in the environment this phase
  was authored in (Docker is not installed) — see `docs/benchmarks/phase-6-reliability.md` for
  exactly what was and was not verified.
