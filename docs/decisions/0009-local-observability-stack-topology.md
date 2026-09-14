# 0009. Local Observability Stack Topology

- Status: Accepted
- Date: 2026-09-13

## Context

[ADR 0005](0005-observability-with-opentelemetry.md) commits SentinelOps to OpenTelemetry as
its instrumentation standard and names Prometheus, Loki, Tempo, and Alertmanager as the
self-hosted backends. Phase 4 has to turn that decision into a concrete, runnable local
topology: how telemetry gets from the incident service to each backend, which service talks
to which, and how Grafana ties them together for a developer debugging one request. As with
every other phase ([ADR 0003](0003-local-first-infrastructure.md)), the topology must run
entirely on local Docker Compose resources with no paid service or external network
dependency.

## Decision

Telemetry flows through a single OpenTelemetry Collector rather than each backend scraping or
receiving directly from the incident service (except metrics, which Prometheus also scrapes
directly — see below):

- The incident service exports **traces** and **logs** over OTLP (gRPC) to the Collector.
  A signal-specific pipeline in the Collector (`memory_limiter` + `batch` processors) then
  forwards traces to Tempo's own OTLP receiver and logs to Loki's native OTLP ingestion
  endpoint (`/otlp`).
- **Metrics** take two paths, both already required by the codebase and both useful: the
  incident service exposes Micrometer metrics at `/actuator/prometheus`, which Prometheus
  scrapes directly (the simplest, most standard Spring Boot path); separately, the Collector
  also exposes any metrics it receives via OTLP as a second Prometheus scrape target
  (`otel-collector:8889`), for parity with any future non-Spring-Boot service that only
  speaks OTLP.
- Every component runs as its own Compose service behind the `observability` profile, with
  pinned image versions, 127.0.0.1-only port bindings, health checks, and named volumes for
  Prometheus and Grafana state (see `infrastructure/docker/docker-compose.yml`).
- Grafana is provisioned (not click-configured) with all three datasources, plus
  `tracesToLogsV2`/derived-field wiring so a trace found in Tempo can jump to its correlated
  Loki log lines and vice versa, using `service.name` and the log body's `trace_id` field —
  never a Loki label — as the join key. Loki labels stay fixed to `service`, `environment`,
  and `level`; trace and correlation IDs live in the structured log body precisely so they
  never explode Loki's label cardinality.
- Alertmanager receives Prometheus alerts but is configured with a single local no-op
  receiver — nothing is routed to email, Slack, PagerDuty, or any other external system.
  Alerts remain inspectable through Alertmanager's own local UI/API.
- The incident service always attempts to export telemetry to
  `http://otel-collector:4317` regardless of whether the `observability` profile is running;
  if the Collector is unreachable, the OpenTelemetry SDK simply drops telemetry after a short
  timeout rather than failing application requests. This keeps the `app` and `observability`
  profiles independently startable, matching how the `console` profile is already optional
  relative to `app`.

## Consequences

- A single Collector configuration file is the one place that decides where telemetry goes,
  making it straightforward to point a future non-local backend at the same pipeline (per
  ADR 0005) without touching the incident service itself.
- Correlating a request end-to-end (HTTP request → logs → trace → metrics) works with a
  single value carried three ways: the `X-Correlation-ID` header/MDC field (existing,
  Phase 3) and the W3C `traceparent` trace ID that Micrometer Tracing now attaches to the
  same log lines and to the outgoing HTTP/Kafka context. See
  `docs/development/observability.md` for the operator-facing walkthrough.
- Because metrics are scraped rather than pushed through the Collector, Prometheus is only
  as fresh as its 15s scrape interval — this is an accepted local-development tradeoff, not
  suitable for a real SLO evaluation pipeline (see Phase 6).
- Running the full stack (six additional containers) has a real memory/CPU cost on a
  developer laptop; every container carries a conservative resource limit, and the
  `observability` profile is opt-in — the platform and incident service work without it.
