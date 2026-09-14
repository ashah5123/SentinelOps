# 0010. Incremental Ingestion, Evidence Normalization, and Deterministic Correlation

- Status: Accepted
- Date: 2026-09-13

## Context

Phase 5 introduces the telemetry-correlation-service: it must continuously pull metrics, logs,
and traces from Prometheus, Loki, and Tempo; consume deployment and dependency-change events;
reduce all of that into one searchable evidence model; and, on every detected incident,
deterministically figure out which evidence is actually relevant. Several design questions
recur across all of this and are easiest to settle once, in one place, rather than per-adapter:

1. How does polling stay incremental (not re-fetching everything every cycle) without dropping
   telemetry that arrives slightly late relative to the previous poll's window?
2. What does "the same evidence" mean across three structurally different backends, so
   redelivery/re-polling doesn't create duplicates?
3. How does correlation stay deterministic, explainable, and free of any AI/ML dependency,
   consistent with this project's later-phase boundaries (SLO/anomaly detection is Phase 6;
   AI-driven investigation is Phase 7)?
4. What happens when one backend is slow or down during a polling cycle?

## Decisions

### Incremental ingestion via watermark + overlap window

Each `(source, monitored service)` pair has its own `IngestionCheckpoint.watermark` — the UTC
instant through which evidence has been durably persisted. Every polling cycle queries from
`watermark - overlapWindow` through now, then advances the watermark to "now" only after
persistence for that cycle has committed. Re-querying the overlap window on every cycle means a
metric/log/trace that arrives after its "true" timestamp's cycle already ran is still picked up
on the next cycle, rather than silently lost at the window boundary. A cycle that fails before
persistence completes never advances its watermark, so the next cycle retries the same window —
this is why persistence and the watermark update happen in one transaction (see
`IngestionCheckpointService`).

### Deterministic fingerprinting for deduplication

Every normalized `Evidence` record carries a `fingerprint` — a SHA-256 hash of the fields that
identify "the same underlying fact" for that evidence type (service + metric name + timestamp +
bounded labels for a metric; service + timestamp + summary for a log line; trace ID + span ID
for a trace; the source event ID for a deployment or dependency change). The database enforces
uniqueness on this column. Re-querying the same overlap window, or reprocessing a duplicate
Kafka delivery, produces the same fingerprint and is therefore rejected as a duplicate rather
than inserted twice — this is what makes incremental polling and at-least-once event delivery
both safely idempotent without a separate "have I seen this before" side-channel per source.

### Correlation is rule-based, not AI-driven

`CorrelationEngine`/`CorrelationScorer` implement fixed, configurable-weight rules only: exact
affected-service match, time proximity to detection, matching trace/correlation ID, a recent
deployment to the affected service, evidence from a directly connected service (one dependency
hop by default), error/failed-span status, and elevated metric signals. No machine-learning
model, embedding, or LLM call is involved anywhere in this phase. Every score is accompanied by
a plain-text explanation listing exactly which rules fired and their weighted contribution, so a
human reviewer can audit *why* a piece of evidence was surfaced. A correlation score reflects
proximity and connection to an incident — it is never described as a confirmed root cause, here
or in the events this service publishes (see `docs/events/telemetry-correlation-events.md`).
Root-cause analysis backed by retrieval and inference is explicitly Phase 7's job, not this
service's.

### Per-source failure isolation

`IngestionScheduler` runs each `(source, monitored service)` pair independently and catches
exceptions per pair: a Loki or Tempo outage is recorded as a polling failure (metric + log +
checkpoint left unadvanced) but never prevents Prometheus evidence — or any other source/service
pair — from being processed in the same cycle. Each backend client additionally wraps its HTTP
call in a Resilience4j circuit breaker and bounded exponential-backoff retry, so a struggling
backend degrades to "temporarily skipped" rather than cascading into repeated slow requests.

## Consequences

- Correlation quality is bounded by how well the fixed rule set and configured weights match
  real incidents; unlike a learned model it cannot improve from data, but it is fully
  inspectable, testable, and reproducible — deliberately preferred for a project whose
  observability platform must itself be trustworthy and auditable (see the human-approval
  principle in [ADR 0004](0004-human-approved-remediation.md), which this phase's "no
  root-cause claim" rule is a smaller instance of).
- The watermark/overlap-window approach trades some duplicate *querying* (the overlap window is
  re-fetched every cycle) for a strong guarantee against duplicate *storage* and missed
  boundary events; this is the right trade for correctness over query volume at local-development
  scale.
- Every evidence-producing path (three backend adapters, two Kafka consumers) must compute a
  fingerprint before persistence — a new evidence source added in a later phase must follow the
  same pattern, documented in `services/telemetry-correlation-service/README.md`.
- Single-instance backpressure (an `AtomicBoolean` guard skipping an overlapping scheduled tick)
  is sufficient for this phase's single-instance local deployment but would need a distributed
  lock if this service were ever run with multiple replicas — noted as a known limitation, not
  solved here.
