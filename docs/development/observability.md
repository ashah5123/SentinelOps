# SentinelOps Local Observability Stack (Phase 4)

Status: baseline observability wired into the incident service. This
document describes the OpenTelemetry Collector, Prometheus, Grafana,
Loki, Tempo, and Alertmanager stack introduced in Phase 4, and how the
incident service's metrics, logs, and traces flow into it.

All components run locally via Docker Compose, with no paid APIs,
hosted monitoring platforms, or external telemetry endpoints of any
kind. See [ADR 0005](../decisions/0005-observability-with-opentelemetry.md)
for why OpenTelemetry was chosen and
[ADR 0009](../decisions/0009-local-observability-stack-topology.md) for
the concrete local topology decisions this document walks through.

## Component responsibilities

| Component | Role |
|---|---|
| OpenTelemetry Collector | Receives OTLP traces and logs from the incident service; batches them and forwards traces to Tempo and logs to Loki. Also exposes its own metrics for Prometheus to scrape. |
| Prometheus | Scrapes and stores metrics from the incident service (`/actuator/prometheus`) and the Collector; evaluates alerting rules. |
| Grafana | Visualizes metrics/logs/traces through provisioned datasources and the two Phase 4 dashboards. |
| Loki | Stores structured logs shipped from the incident service via the Collector. |
| Tempo | Stores distributed traces shipped from the incident service via the Collector. |
| Alertmanager | Receives firing alerts from Prometheus; local-only, no external notification channel is configured. |

## Starting and stopping the stack

The observability stack is its own Compose profile, independent of
`app` (the incident service) and `console` (Redpanda Console):

```bash
cp .env.example .env   # if you haven't already
make observability-config   # validate the compose configuration
make observability-up       # start otel-collector, prometheus, loki, tempo, alertmanager, grafana
make incident-up            # start the incident service (if not already running)
```

Stopping and status:

```bash
make observability-status   # show container health
make observability-logs     # tail recent logs from all observability containers
make observability-down     # stop containers, KEEP all volumes
```

`make observability-down` never deletes `sentinelops-prometheus-data`,
`sentinelops-grafana-data`, `sentinelops-loki-data`,
`sentinelops-tempo-data`, or `sentinelops-alertmanager-data`. There is
no `observability-clean` target — use the existing `infra-clean`
pattern manually (`docker compose ... down --volumes` against just
these named volumes) if you ever intentionally need to discard
observability history; nothing in Phase 4 tooling does this
automatically.

The incident service always attempts to export telemetry to the
Collector at `http://otel-collector:4317`; if the `observability`
profile isn't running, the OpenTelemetry SDK simply drops telemetry
after a short timeout rather than failing requests. It is therefore
safe to start `app` and `observability` in either order, or run one
without the other.

## Local endpoints (all bound to 127.0.0.1 only)

| Service | URL | Purpose |
|---|---|---|
| Grafana | http://127.0.0.1:3001 (`GRAFANA_PORT`) | Dashboards, datasource explore, alert view. Login: `GRAFANA_ADMIN_USER`/`GRAFANA_ADMIN_PASSWORD` from `.env` (defaults `admin`/local placeholder). |
| Prometheus | http://127.0.0.1:9090 (`PROMETHEUS_PORT`) | Metrics query UI, target/rule status (`/targets`, `/rules`). |
| Alertmanager | http://127.0.0.1:9093 (`ALERTMANAGER_PORT`) | Active/silenced alert view. |
| Loki | http://127.0.0.1:3100 (`LOKI_PORT`) | Log query API (used via Grafana Explore, not usually browsed directly). |
| Tempo | http://127.0.0.1:3200 (`TEMPO_PORT`) | Trace query API (used via Grafana Explore). |
| OpenTelemetry Collector (OTLP/gRPC) | http://127.0.0.1:4317 (`OTEL_COLLECTOR_GRPC_PORT`) | Where the incident service (or a host-run instance) sends telemetry. |
| OpenTelemetry Collector (OTLP/HTTP) | http://127.0.0.1:4318 (`OTEL_COLLECTOR_HTTP_PORT`) | HTTP alternative to the gRPC endpoint. |
| incident-service Actuator | http://127.0.0.1:8081/actuator/prometheus | Raw scraped metrics, for debugging what Prometheus sees. |

## How metrics, logs, and traces flow

```
incident-service (Micrometer + OpenTelemetry SDK)
  │
  ├─ metrics ──► /actuator/prometheus ──scraped by──► Prometheus ──► Grafana
  │
  ├─ traces  ──OTLP/gRPC──► otel-collector ──OTLP/gRPC──► Tempo ──► Grafana
  │
  └─ logs    ──OTLP/gRPC──► otel-collector ──OTLP/HTTP──► Loki  ──► Grafana

otel-collector also exposes its own received-metrics as a second
Prometheus scrape target (otel-collector:8889), and Alertmanager
receives alerts evaluated by Prometheus from the rules in
infrastructure/docker/observability/prometheus/rules/.
```

Metrics are Micrometer-based: Spring Boot Actuator + Micrometer
automatically publish HTTP request count/duration (`http_server_requests_seconds_*`),
JVM memory/GC (`jvm_memory_used_bytes`, `jvm_gc_pause_seconds_*`),
HikariCP connection-pool state (`hikaricp_connections_*`), and Kafka
client metrics, once `micrometer-registry-prometheus` is on the
classpath (Phase 4 adds it). SentinelOps adds a small number of custom
metrics on top — see "Custom metric catalog" below.

Traces come from two sources: Spring's automatic instrumentation
(HTTP requests, and Kafka producer/consumer spans via
`spring.kafka.template.observation-enabled` /
`spring.kafka.listener.observation-enabled`), the
`datasource-micrometer-spring-boot` library (one span per JDBC
statement), and a small explicit-span helper
(`com.sentinelops.incident.observability.Spans`) wrapping incident
creation, status transitions, anomaly-event processing, and outbox
publishing.

Logs are exported over OTLP directly from the JVM process via an
`OpenTelemetryAppender` attached in `logback-spring.xml`, alongside
(not instead of) the existing ECS-formatted JSON console output — so
`docker compose logs incident-service` is unaffected.

## Custom metric catalog

All tags below are small, fixed sets — never an incident ID,
correlation ID, exception message, or other user-controlled/unbounded
value.

| Metric | Type | Tags | Meaning |
|---|---|---|---|
| `sentinelops.incidents.created` | counter | `severity` | Incidents created, by severity (SEV1–SEV4). |
| `sentinelops.incidents.transitioned` | counter | `from_status`, `to_status` | Incident status transitions. |
| `sentinelops.anomaly.events.processed` | counter | `outcome` (`created`\|`duplicate`\|`failed`) | Outcomes of consuming `telemetry.anomaly.v1`. |
| `sentinelops.outbox.published` | counter | `topic`, `outcome` (`success`\|`failure`) | Transactional outbox publish attempts. |
| `sentinelops.outbox.publish.duration` | timer | `topic` | Time to publish one outbox event to Kafka. |

## Finding a request across Grafana, Tempo, and Loki

Every incident-service HTTP response carries an `X-Correlation-ID`
header (existing Phase 3 behavior — see `CorrelationIdFilter`), and
every log line and trace produced while handling that request carries
both that correlation ID and a W3C trace ID.

To follow one request:

1. Note the `X-Correlation-ID` from the response (or generate test
   traffic — see below).
2. In Grafana, open **Explore** against the **Loki** datasource and
   query `{service="incident-service"} |= "<correlation-id>"`.
3. Each matching log line's `trace_id` field is rendered as a
   clickable **TraceID** derived field (configured in Grafana's Loki
   datasource provisioning) — click it to jump straight to that trace
   in Tempo.
4. From the trace view in Tempo, use **Trace to logs** to jump back to
   every log line correlated with that specific trace/span — this
   works because Tempo's datasource is provisioned with
   `tracesToLogsV2` pointed at Loki, filtered by `service.name`.
5. To see the metrics context for the same window, switch to the
   **Prometheus** datasource and query e.g.
   `http_server_requests_seconds_count{job="incident-service"}` over
   the same time range shown in the trace.

### How correlation IDs and trace IDs relate

They are deliberately two different identifiers with two different
lifetimes:

- **Correlation ID** (`X-Correlation-ID`): supplied by the caller or
  generated by `CorrelationIdFilter`; a single correlation ID can span
  multiple requests/traces if a caller reuses it (e.g. a client retry).
  It has existed since Phase 3 and predates OpenTelemetry in this
  codebase.
- **Trace ID**: generated by the OpenTelemetry SDK per distinct
  request/consumer-invocation trace; propagated via the W3C
  `traceparent` header over HTTP and via Kafka record headers.

Both are attached to every structured log line's body (never as a
Loki label — see below), so either one can be used as the join key
between Grafana panels, Tempo, and Loki.

## Generating test traffic

With the incident service and observability stack both running:

```bash
curl -s -X POST http://127.0.0.1:8081/api/v1/incidents \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: test-traffic-1' \
  -H 'X-Correlation-ID: manual-test-1' \
  -d '{
        "title": "Test incident for observability",
        "description": "Generated to exercise metrics/logs/traces",
        "severity": "SEV3",
        "source": "manual-test",
        "affectedService": "incident-service"
      }'
```

Or simply run the smoke test, which generates a real request and
verifies it is visible end-to-end:

```bash
make observability-smoke
```

## Inspecting active alerts

- Prometheus **Alerts** page: http://127.0.0.1:9090/alerts — shows
  rule evaluation state (inactive/pending/firing).
- Alertmanager UI: http://127.0.0.1:9093 — shows alerts actually
  routed to (and grouped by) the local no-op receiver.
- Configured alerts (see
  `infrastructure/docker/observability/prometheus/rules/sentinelops-alerts.yml`):
  `SentinelOpsIncidentServiceDown`, `SentinelOpsHighHttp5xxRate`,
  `SentinelOpsHighRequestLatency`, `SentinelOpsKafkaConsumerErrors`,
  `SentinelOpsOutboxPublishFailures`, `SentinelOpsAnomalyProcessingFailures`.

**All thresholds are local-development defaults**, chosen for a
single-instance, low-traffic environment (e.g. 5% 5xx rate, 1s p95
latency). A real deployment must derive thresholds from actual
workload/SLO data per service — do not carry these numbers forward
without re-deriving them (this is exactly the job of the Phase 6
detection engine, not this baseline).

## Low-cardinality Loki labels

Loki is configured with `allow_structured_metadata` and a
`max_label_names_per_series` limit specifically so labels stay to
`service`, `environment`, and `level`. Trace IDs, correlation IDs, and
any other per-request identifier live in the JSON log body, never as
a label — putting a per-request value in a Loki label would create an
unbounded number of streams and degrade Loki badly under real load.

## Safe cleanup

- `make observability-down` — stops containers, **keeps all volumes**.
  Safe to run at any time.
- There is no destructive `observability-clean` target. If you need to
  discard observability history/state, remove the specific named
  volumes yourself (`sentinelops-prometheus-data`,
  `sentinelops-grafana-data`, `sentinelops-loki-data`,
  `sentinelops-tempo-data`, `sentinelops-alertmanager-data`) — this is
  intentionally a manual, explicit action.

## Resource requirements

Approximate steady-state memory footprint for the observability
profile alone, based on configured per-service limits: otel-collector
384 MB, Prometheus 512 MB, Loki 512 MB, Tempo 512 MB, Alertmanager
128 MB, Grafana 384 MB — **~2.4 GB** total, on top of the Phase 2/3
platform's own ~3.5–4 GB (see `docs/development/local-platform.md`).
Running everything (`console` + `app` + `observability`) at once on a
modest laptop is realistic but leaves little headroom for other work —
stop profiles you're not actively using.

## Troubleshooting (macOS / Docker Desktop)

- **`docker: command not found` / observability containers never
  start**: Docker Desktop isn't installed or running — see
  `docs/development/local-platform.md`'s troubleshooting section.
- **Grafana shows "unhealthy" datasource / no data**: confirm
  Prometheus/Loki/Tempo report healthy first (`make observability-status`);
  Grafana depends on all three being healthy before it starts.
- **No traces in Tempo**: confirm the incident service actually has
  the `observability` profile reachable — check
  `OTEL_EXPORTER_OTLP_ENDPOINT` in the running container's environment
  and `make observability-logs` for otel-collector connection errors.
  Also confirm `management.tracing.sampling.probability` is `1.0` in
  the active Spring profile (it is, by default, in `application.yml`).
- **Loki query returns nothing immediately after a request**: log
  export is batched (5s `batch` processor timeout in the Collector) —
  wait a few seconds and retry, or use the smoke test's bounded-retry
  polling rather than a single query.
- **Port already in use**: another local process may already be bound
  to a Phase 4 port (3001, 9090, 9093, 3100, 3200, 4317, 4318). Change
  the relevant `*_PORT` value in `.env` and re-run `make observability-up`.
- **High memory pressure on a low-RAM machine**: stop the
  `observability` profile when not actively debugging telemetry
  (`make observability-down`); it is fully optional for day-to-day
  incident-service development.

## Current implementation status and remaining limitations

- **Implemented**: the full Collector → Prometheus/Loki/Tempo/Alertmanager
  → Grafana pipeline; incident-service metrics, traces, and structured
  logs; two provisioned Grafana dashboards; Prometheus alerting rules;
  an idempotent smoke test; unit tests for the new metrics/tracing
  helpers.
- **Blocked in the environment this phase was authored in**: Docker
  was not installed, so no container was actually built, started, or
  scraped, and the end-to-end runtime acceptance criteria in the
  Phase 4 validation checklist (containers healthy, Prometheus targets
  up, a real trace/log visible, Grafana dashboards rendering live
  data, alert rules loaded, restart-and-persist check) have **not**
  been executed or confirmed. `make observability-config` (static
  Compose validation), all YAML/JSON config parsing, the Maven build,
  and the full non-Docker unit test suite have been run and pass. This
  must be completed and confirmed, exactly like the outstanding Phase
  2 and Phase 3 runtime-verification items, before Phase 4 is
  considered fully done.
- **Not in scope for Phase 4** (deferred to later phases per the
  roadmap): SLO evaluation/anomaly detection (Phase 6), the telemetry
  ingestion and correlation service (Phase 5), any AI-driven analysis
  of this telemetry, authentication in front of any of these UIs, and
  Kubernetes packaging (Phase 10). None of that is implemented here.
