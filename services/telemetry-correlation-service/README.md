# telemetry-correlation-service

SentinelOps's telemetry ingestion and correlation service (Phase 5). Java 21, Spring Boot.

## Responsibilities

- Poll Prometheus, Loki, and Tempo incrementally for configured monitored services, normalizing
  results into one shared evidence model (`METRIC`, `LOG`, `TRACE`, `DEPLOYMENT`, `DEPENDENCY`).
- Consume `deployment.changed.v1` and `service.dependency.changed.v1` idempotently, maintaining
  deployment history and the current service-dependency graph (with full change history).
- On every `incident.detected.v1` event, deterministically correlate telemetry, recent
  deployments, and connected services to that incident using fixed, explainable scoring rules —
  no machine learning or LLM inference (see
  [ADR 0010](../../docs/decisions/0010-incremental-ingestion-and-correlation.md)).
- Publish `incident.evidence.correlated.v1` through a transactional outbox for every piece of
  evidence a correlation run selects — see
  [docs/events/telemetry-correlation-events.md](../../docs/events/telemetry-correlation-events.md).
- Expose a versioned REST API for querying evidence, deployments, dependencies, and correlation
  results — see [docs/api/telemetry-correlation-service.md](../../docs/api/telemetry-correlation-service.md).
- Emit its own metrics, traces, and structured logs through the Phase 4 observability stack.

Not implemented in this phase: SLO evaluation or anomaly detection (Phase 6), AI-driven
investigation, RAG, or local LLM inference (Phase 7), authentication, human approval, or
remediation execution (Phase 8), an operator dashboard (Phase 9), and Kubernetes/Helm packaging
or AWS provisioning (Phase 10).

## ⚠️ Local-development security boundary

This service has no authentication or authorization. It must only ever run bound to
`127.0.0.1` (the default in `infrastructure/docker/docker-compose.yml`) and must never be
exposed on a public or shared network. See `docs/api/telemetry-correlation-service.md`.

## Architecture

```
Prometheus ──┐                                    ┌──► incident.evidence.correlated.v1 (outbox)
Loki       ──┼─► IngestionScheduler ─► Evidence ───┤
Tempo      ──┘   (per-source runners,   (Postgres) │
                  checkpointed, isolated)           │
                                                     │
deployment.changed.v1 ──► DeploymentService ────────┤
service.dependency.changed.v1 ──► DependencyGraphService
                                                     │
incident.detected.v1 ──► CorrelationEngine ─────────┘
                          (reads Evidence, Deployments,
                           dependency graph; scores; persists
                           CorrelationResult)
```

Package layout (`com.sentinelops.telemetry`):

| Package | Contents |
|---|---|
| `domain` | JPA entities and enums: `Evidence`, `Deployment`, `ServiceDependency`(`History`), `IngestionCheckpoint`, `CorrelationResult`/`CorrelationEvidence`, `OutboxEvent`, `ProcessedEvent`. |
| `adapters.{prometheus,loki,tempo}` | One HTTP client + response DTOs + normalizer per backend. |
| `application` | Ingestion runners/scheduler, checkpoint service, fingerprinting, dependency graph, deployment service, correlation engine/scorer, outbox writer, query services. |
| `infrastructure.kafka` | Kafka listeners (deployment, dependency, incident-detected) and the outbox publisher. |
| `infrastructure.persistence` | Spring Data repositories and JPA specifications. |
| `config` | `@ConfigurationProperties`, Jackson, Kafka, backend HTTP clients, OpenAPI. |
| `web.{controller,dto,error,filter}` | REST API, DTOs (never entities), RFC 9457 error handling, correlation-ID filter. |
| `observability` | Custom Micrometer metrics and a tracing-span helper. |
| `events` | The event envelope and every payload this service publishes/consumes. |

## Telemetry source adapters

Each adapter (`PrometheusClient`/`LokiClient`/`TempoClient`) is wrapped in a Resilience4j
circuit breaker and bounded exponential-backoff retry (instances named `prometheus`, `loki`,
`tempo` in `application.yml`), enforces a configurable max response size, and classifies
failures via a dedicated exception type (`PrometheusQueryException`, etc.). A failing backend
never blocks the others in the same polling cycle — see `IngestionScheduler`.

- **Prometheus**: runs every configured PromQL query (`sentinelops.telemetry.prometheus-queries`
  — request rate, error rate, p95 latency, availability, JVM heap, Kafka listener errors by
  default) via `/api/v1/query_range`. Non-finite values (`NaN`/`Inf`) are rejected; only a fixed
  allow-list of label keys is kept as evidence attributes.
- **Loki**: runs one configurable LogQL query (`sentinelops.telemetry.loki-query`) via
  `/loki/api/v1/query_range`. Log lines are parsed as JSON (the Phase 4 structured-log format);
  only a bounded set of fields is extracted (level, trace ID, correlation ID, event type, error
  code, message). Summaries are length-bounded, control characters are stripped, and a
  defense-in-depth regex redacts anything that looks like a credential/authorization header.
- **Tempo**: `/api/search` for coarse, root-trace-level evidence during routine polling (bounded
  volume per cycle); `/api/traces/{traceId}` for full span-level detail, fetched on demand by
  `TraceLookupService` (e.g. for a trace ID found in a Loki log line or during correlation) —
  preserves trace ID, span ID, parent span ID, service, operation, duration, and status.

## Polling and checkpoints

Each `(source, monitored service)` pair has its own watermark (`IngestionCheckpoint`). Every
cycle queries `[watermark - overlapWindow, now)`, deduplicates by `Evidence.fingerprint`, and
advances the watermark only after persistence commits — see
[ADR 0010](../../docs/decisions/0010-incremental-ingestion-and-correlation.md) for the full
rationale. A single-instance `AtomicBoolean` guard in `IngestionScheduler` skips a scheduled
tick entirely if the previous cycle is still running.

Key settings (`sentinelops.telemetry.ingestion.*` / env vars — see `.env.example`):
`polling-interval` (`TELEMETRY_POLLING_INTERVAL`, default 30s), `query-window`
(`TELEMETRY_QUERY_WINDOW`, 60s), `overlap-window` (`TELEMETRY_OVERLAP_WINDOW`, 15s),
`max-records-per-cycle` (`TELEMETRY_MAX_RECORDS_PER_CYCLE`, 2000), `batch-size`
(`TELEMETRY_BATCH_SIZE`, 200).

## Normalized evidence model

See `Evidence.java`. Every record has an `evidenceType`, `sourceSystem`, `sourceService`,
`observedAt`/`ingestedAt`, a sanitized `summary`, a `sourceReference`, and a unique
`fingerprint`; type-specific fields (`metricName`/`metricValue`, `traceId`/`spanId`, `severity`,
`deploymentId`, `correlationId`) are populated only where relevant. `attributes` is a bounded
JSONB map for supplementary, low-cardinality data — never a place for arbitrary or sensitive
payloads.

## Correlation rules and scoring

See `CorrelationScorer.java` and
[ADR 0010](../../docs/decisions/0010-incremental-ingestion-and-correlation.md). Weights are
configurable under `sentinelops.telemetry.correlation.weights` (each bounded 0–100 by
validation). Every scored evidence record carries a plain-text explanation of exactly which
rules contributed. **A correlation score reflects rule-based proximity and connection to an
incident — it is never a confirmed root cause.**

## Database schema

Owns the `telemetry` PostgreSQL schema, provisioned idempotently by
`infrastructure/docker/postgres/provisioning/telemetry-schema-init.sh` — run as its own one-shot
Compose service (`postgres-schema-init`) on every `docker compose up`, rather than a
`docker-entrypoint-initdb.d` script, specifically so it also provisions the schema correctly
against a **pre-existing** Postgres volume created during Phase 2/3 (which predates this
service and never ran that hook). Flyway then owns and validates all tables (`ddl-auto:
validate`, no Hibernate auto-DDL) — see `src/main/resources/db/migration/`.

## Building and running locally

```bash
./mvnw -q verify              # format check, tests, package
```

### Running against the platform infrastructure

```bash
cp ../../.env.example ../../.env   # from the repo root, if not already done
make correlation-up                # from the repo root: starts infra + this service
```

### Running as a container

```bash
make correlation-image
```

## Configuration

All configuration is environment-driven — see the root `.env.example` for the full list
(`TELEMETRY_*`, `CORRELATION_*`, `PROMETHEUS_URL`/`LOKI_URL`/`TEMPO_URL`) plus
`sentinelops.telemetry.*` properties in `src/main/resources/application.yml`. Missing required
configuration fails service startup rather than silently falling back to an unsafe default.

## Retry and dead-letter handling

Inbound Kafka consumption (`deployment.changed.v1`, `service.dependency.changed.v1`,
`incident.detected.v1`) uses bounded exponential backoff then dead-letter publication, identical
to the incident service's convention (see ADR 0008). Outbound publication
(`incident.evidence.correlated.v1`) uses the same transactional-outbox pattern as the incident
service (ADR 0007) — failed publish attempts are retried with backoff; rows that exhaust
`sentinelops.telemetry.outbox.max-attempts` are marked `FAILED` and kept for manual inspection:

```sql
SELECT id, topic, event_type, attempt_count, last_error, created_at
FROM telemetry.outbox_events
WHERE status = 'FAILED'
ORDER BY created_at DESC;
```

## Observability

Emits metrics (`GET /actuator/prometheus`), distributed traces, and structured logs via
Micrometer and OpenTelemetry — see
[`docs/development/observability.md`](../../docs/development/observability.md) for the full
metric catalog and dashboard panels. `OTEL_EXPORTER_OTLP_ENDPOINT` (default
`http://localhost:4317`, or `http://otel-collector:4317` inside Compose) controls where
telemetry is sent; the service starts and serves traffic normally even if that endpoint is
unreachable.

## API and event documentation

- [`docs/api/telemetry-correlation-service.md`](../../docs/api/telemetry-correlation-service.md)
- [`docs/events/telemetry-correlation-events.md`](../../docs/events/telemetry-correlation-events.md)
- [`docs/events/event-envelope.md`](../../docs/events/event-envelope.md)

## Known limitations

- No authentication/authorization (see security boundary notice above).
- Single-instance backpressure only (`AtomicBoolean` guard) — a multi-instance deployment would
  need a distributed lock around each polling cycle instead.
- Tempo's periodic polling captures root-trace-level evidence only, to bound per-cycle volume;
  full span-level detail is fetched on demand (trace lookup), not on every scheduled cycle.
- Correlation considers direct dependency connections up to a configurable depth (default 1 hop)
  — it does not attempt full-graph or transitive-impact analysis.
- Metric label attributes are limited to a small, fixed allow-list; a metric whose useful signal
  lives in a label outside that list requires updating `PrometheusNormalizer`'s allow-list, not
  a configuration change.
- Correlation scores are heuristic and rule-based; they are not validated against real incident
  outcomes in this phase and must not be presented as root-cause confirmation.
