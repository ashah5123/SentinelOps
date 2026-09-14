# 0011. Reliability and Failure-Recovery Hardening

- Status: Accepted
- Date: 2026-09-14

## Context

Phases 3 and 5 already implemented a transactional outbox (ADR 0007) and idempotent, at-least-once
Kafka consumption (ADR 0008) for both Java services. Those designs were correct in shape but had
three concrete gaps once examined under actual failure conditions rather than the happy path:

1. `OutboxPublisher.publishDueEvents()` claimed a batch with `SELECT ... FOR UPDATE SKIP LOCKED`
   and then ran the entire batch's Kafka sends — real network I/O, each with its own timeout —
   inside that same open database transaction. A slow or unreachable broker would hold the
   transaction (and the row locks) open for the full duration of every send in the batch.
2. Retry backoff (both the outbox's and the Kafka listener's) was purely exponential, with no
   jitter — many partitions or rows failing at the same moment (e.g. right when a broker outage
   ends) would retry in lockstep.
3. Readiness (`/actuator/health/readiness`) only reflected the application's own
   `ReadinessState`, not actual PostgreSQL or Kafka-compatible broker connectivity — a health
   indicator for each already existed (or was added) but wasn't wired into the readiness group,
   so an orchestrator polling readiness would see "ready" even while a required dependency was
   down.

Separately, the `processed_events` idempotency tables (used for exactly this purpose since Phase
3) had no retention strategy and would grow without bound for the lifetime of each service, and
neither service distinguished a permanent failure (malformed JSON, an invalid enum value) from a
transient one when deciding whether to retry — both were retried identically before falling
through to the dead-letter topic.

## Decisions

### Claim, publish, and finalize as three separate transactions

`OutboxPublisher` now claims a batch in one short transaction (`OutboxTransactions.claimBatch`),
which also "leases" each claimed row by moving `next_attempt_at` forward — protecting it from a
second instance's claim without needing to hold a lock open. The Kafka send happens with **no**
open transaction. Success or failure is then recorded in a second short transaction
(`markPublished` / `recordFailure`). If the process crashes between claim and finalize, the lease
simply expires and the row becomes claimable again — no new failure mode is introduced; it is the
same at-least-once, redelivery-safe behavior the outbox already documented, just without holding
a transaction open for network I/O. `OutboxTransactions` exists as its own Spring bean rather than
private methods on `OutboxPublisher` specifically because `@Transactional` only takes effect
through the enclosing bean's proxy — an internal method call from `OutboxPublisher` to itself would
silently run without a transaction.

### Jittered exponential backoff everywhere retries happen

`JitteredExponentialBackOff` (one per service, matching the existing "no shared runtime code
between services" convention from ADR 0002) wraps Spring's `ExponentialBackOff` and adds a
configurable fractional jitter to every computed interval. Applied to both the outbox's retry
backoff and the Kafka listener's `DefaultErrorHandler` backoff. A jitter fraction of `0.0`
reproduces the original, non-jittered behavior exactly, so this is a strictly additive change.

### Non-retryable exceptions skip straight to the dead-letter topic

`DefaultErrorHandler.addNotRetryableExceptions(JsonProcessingException.class,
IllegalArgumentException.class)` is now configured on both services' Kafka listener container
factories. Malformed JSON and invalid enum/validation values can never succeed on retry — retrying
them anyway only delays dead-letter routing and wastes the retry budget that a genuinely transient
failure (a momentary database or network hiccup) needs. `IllegalArgumentException` specifically
covers `IncidentSeverity.valueOf(...)` on an invalid severity string and
`DependencyGraphService`'s self-dependency/validation checks — both are permanent by construction.

### Readiness reflects real dependency health

`management.endpoint.health.group.readiness.include` now lists `readinessState,db,kafkaConnectivity`
for both services (the incident service already had a `KafkaConnectivityHealthIndicator`; the
telemetry-correlation service gained one to match). `db` is Spring Boot's own auto-configured
DataSource health indicator. Readiness now fails (503) whenever PostgreSQL or the broker is
unreachable, which is what a real deployment needs to stop routing traffic to an instance that
cannot actually do its job.

### Retention for processed-event idempotency records

`ProcessedEventRetention` (one per service) deletes `processed_events` rows older than a
configurable retention window (default 8 days — comfortably longer than the broker's own topic
retention, so a message somehow redelivered after a long delay is still recognized as a
duplicate) on a configurable interval. Without this, the table would grow forever.

## Consequences

- All of the above are additive/configurable changes with safe defaults; no public API, event
  schema, or database table was changed. `OutboxEvent.lease(...)` reuses the existing
  `next_attempt_at` column rather than requiring a migration.
- The claim/publish/finalize split means a single polling cycle now makes at least two database
  round trips per batch instead of one long transaction — an intentional trade of a little more
  DB chatter for never holding a transaction open across a network call, which is the more
  important property under real failure conditions (a stuck transaction blocks `FOR UPDATE SKIP
  LOCKED` visibility for the whole batch, not just the slow row).
- New Micrometer metrics (outbox backlog, oldest-pending age, dead-lettered counts, retry-attempt
  counts) give the failure conditions this ADR addresses direct observability — see
  `docs/development/reliability.md` for the full catalog and the alerts built on top of them.
- This work does not change delivery semantics: the system remains at-least-once, not
  exactly-once, and every consumer must still be idempotent — see ADR 0008. Nothing here claims a
  specific quantitative reliability improvement; see `docs/benchmarks/phase-6-reliability.md` for
  what was actually measured versus what remains unverified pending Docker availability.
