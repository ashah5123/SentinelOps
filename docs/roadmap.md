# SentinelOps Roadmap

Each phase below is scoped to be small and independently testable.
A phase is not considered complete until its acceptance criteria are
met. This roadmap will be revised as phases complete and new
information emerges.

## Phase 1 — Foundation (complete)

**Goal:** Establish repository structure, architecture, and
development standards. No application code.

**Acceptance criteria:**
- [x] Repository initialized with `main` as the default branch, correct
  local author configuration, and no secrets committed.
- [x] `.gitignore`, `.editorconfig`, `.gitattributes`, `.env.example`,
  `LICENSE`, `CONTRIBUTING.md`, `SECURITY.md`, and `README.md` exist
  and accurately describe the current (foundation-only) state.
- [x] Architecture is documented (`docs/architecture/system-overview.md`)
  and at least the initial set of ADRs are recorded.
- [x] `make doctor` accurately reports the presence/absence of required
  local tooling without installing or modifying anything.
- [x] `make validate` passes for the checked-in state of the repository.

## Phase 2 — Local data and event-streaming infrastructure

**Goal:** Stand up the free, local data and event-streaming
infrastructure via Docker Compose, with no application services,
authentication, Kubernetes, observability stack, or AI functionality
yet. Identity (Keycloak) and the observability stack are deferred to
later phases so this phase stays small and testable.

**Acceptance criteria:**
- [x] Docker Compose configuration defines PostgreSQL (with pgvector),
  Redis, Redpanda, Redpanda Console (optional profile), and MinIO,
  with pinned image versions, localhost-only port bindings, named
  volumes, health checks, and conservative resource limits.
- [x] PostgreSQL initialization creates a non-superuser application
  role and the `incidents`, `audit`, and `runbooks` schemas, with the
  `vector` extension enabled — no application tables yet.
- [x] Redpanda topic initialization and MinIO bucket initialization
  are idempotent and run automatically via one-time init containers.
- [x] `.env.example` documents every Phase 2 variable with safe,
  clearly-labeled local placeholders.
- [x] `Makefile` provides `infra-config`, `infra-pull`, `infra-up`,
  `infra-down`, `infra-status`, `infra-logs`, `infra-smoke`, and
  `infra-clean`, and a full smoke-test script exists.
- [x] Documentation (`README.md`, `docs/development/local-platform.md`,
  ADR 0006) describes the platform, its ports/volumes/topics/buckets,
  and safe cleanup.
- [ ] End-to-end runtime verification (`make infra-up`, full smoke
  test pass, restart-and-persist check) — **blocked**: Docker was not
  installed in the environment this phase was authored in. This must
  be completed and confirmed before Phase 2 is considered fully done.

## Phase 3 — Incident management service

**Goal:** Build a production-quality incident-management control-plane
service (Java 21, Spring Boot). No frontend, AI investigator,
authentication provider, Kubernetes deployment, or observability
backend yet.

**Acceptance criteria:**
- [x] `Incident` aggregate with enforced lifecycle transitions
  (`IncidentTransitions`), evidence, status history, immutable audit
  records, transactional outbox, and idempotent-consumption tracking.
- [x] Flyway migrations create all required tables, constraints, and
  indexes in the existing `incidents`/`audit` schemas; no
  Hibernate auto-DDL.
- [x] Versioned REST API under `/api/v1` with DTOs (never JPA
  entities), validation, pagination/filtering/sorting, RFC 9457
  problem responses with stable error codes, `Idempotency-Key`
  enforcement, and correlation-ID propagation.
- [x] Consumes `telemetry.anomaly.v1` idempotently; publishes
  `incident.detected.v1` and `audit.event.v1` through a transactional
  outbox (ADR 0007), with bounded-retry + dead-letter handling for
  inbound consumption (ADR 0008).
- [x] Health/readiness/liveness endpoints; graceful shutdown; UTC
  throughout; structured logs; no secrets logged.
- [x] Multi-stage, non-root Dockerfile; integrated into the Phase 2
  Compose environment behind an `app` profile.
- [x] Unit tests (transitions, idempotency, correlation IDs, event
  envelopes, audit) — 41 tests, all passing.
- [x] Repository/migration and full integration/API tests written
  against Testcontainers (PostgreSQL + Kafka-compatible broker).
- [ ] Repository/migration and integration test **execution**, Docker
  image build, and the full local smoke test (create → transition →
  anomaly → outbox → duplicate-delivery check) — **blocked**: Docker
  was not installed in the environment this phase was authored in.
  This must be completed and confirmed before Phase 3 is considered
  fully done.

## Phase 4 — Observability baseline

**Goal:** Stand up the OpenTelemetry Collector, Prometheus, Grafana,
Loki, Tempo, and Alertmanager locally, and wire the incident service's
existing structured logs/correlation IDs into real traces and metrics.

**Acceptance criteria:**
- [x] The incident service emits metrics (Micrometer +
  `micrometer-registry-prometheus`), traces (Micrometer Tracing + the
  OpenTelemetry OTLP bridge, W3C context propagation over HTTP and
  Kafka), and structured logs (existing ECS JSON console output, plus
  an `OpenTelemetryAppender` exporting the same log records over
  OTLP), including custom low-cardinality metrics for incident
  creation/transitions, anomaly-event outcomes, and outbox publish
  outcomes/duration (see `docs/development/observability.md`).
- [x] Docker Compose defines an `observability` profile (OpenTelemetry
  Collector, Prometheus, Grafana, Loki, Tempo, Alertmanager) with
  pinned image versions, localhost-only port bindings, health checks,
  named volumes, and conservative resource limits (ADR 0009).
- [x] Grafana is provisioned (not click-configured) with Prometheus,
  Loki, and Tempo datasources, trace-to-log and log-to-trace
  correlation, and two dashboards ("SentinelOps Service Overview",
  "SentinelOps Incident Processing").
- [x] Prometheus alerting rules cover elevated 5xx rate, request
  latency, service unavailability, Kafka consumer errors, outbox
  publish failures, and anomaly-processing failures, with documented
  local-development-only thresholds; Alertmanager is configured with a
  local-only no-op receiver (no email/Slack/PagerDuty/external
  integration).
- [x] `Makefile` provides `observability-config`, `observability-up`,
  `observability-down`, `observability-status`, `observability-logs`,
  and `observability-smoke`, and an idempotent, bounded-retry smoke
  test exists.
- [x] Unit tests cover the new custom-metrics helper, the tracing-span
  helper (including error handling), and the logback configuration's
  structure; all pre-existing Phase 1–3 tests continue to pass.
- [ ] End-to-end runtime verification (`make observability-up`, full
  observability smoke-test pass, Grafana dashboards rendering live
  data sourced from real requests, alert rules loaded and visible in
  Prometheus/Alertmanager, restart-and-persist check for Prometheus
  and Grafana data) — **blocked**: Docker was not installed in the
  environment this phase was authored in. Static validation (YAML/JSON
  config parsing, `mvn verify` excluding Docker-dependent tests, and
  the Maven package build) was completed and passes. This must be
  completed and confirmed before Phase 4 is considered fully done —
  see `docs/development/observability.md`'s implementation-status
  section for the exact list.

## Phase 5 — Telemetry ingestion and correlation service (current)

**Goal:** Build the Ingestion & Correlation Service (Java) that
normalizes telemetry, deployment events, and dependency metadata into
a shared incident-evidence model, feeding the incident service.

**Acceptance criteria:**
- [x] Service ingests from Prometheus/Loki/Tempo (per-source adapters
  with bounded retry, circuit breakers, and response-size limits) and
  a deployment-event source (`deployment.changed.v1`), and persists
  normalized evidence records with deterministic fingerprint-based
  deduplication and checkpointed, overlap-window incremental polling
  (see ADR 0010).
- [x] Consumes `service.dependency.changed.v1` idempotently, maintains
  the current service-dependency graph with full change history, and
  rejects self-dependency while allowing valid dependency cycles.
- [x] On `incident.detected.v1`, runs deterministic (rule-based, no
  AI/ML) correlation — evidence proximity, matching trace/correlation
  IDs, recent deployments, and dependency-connected services — with
  configurable weights and a persisted, human-readable explanation per
  scored evidence record.
- [x] Publishes `incident.evidence.correlated.v1` through a
  transactional outbox; the incident service consumes it idempotently
  and appends evidence to the correct incident without ever
  overwriting operator-recorded evidence.
- [x] Dedicated `telemetry` PostgreSQL schema, provisioned idempotently
  for both new and pre-existing Postgres volumes; Flyway-managed
  tables (no Hibernate auto-DDL) for evidence, deployments,
  dependencies (current + history), correlation results, ingestion
  checkpoints, processed events, and the outbox.
- [x] Versioned REST API (`/api/v1`) for evidence, deployments,
  dependencies, and correlation results, with pagination, filtering,
  RFC 9457 errors, and OpenAPI documentation; a manual-ingestion
  endpoint gated to the `local-dev` profile only.
- [x] Unit tests (JUnit) covering normalization, fingerprinting,
  checkpoint behavior, correlation scoring, dependency-graph
  validation/traversal, and field sanitization; JSON Schema contract
  tests for every new event payload; integration tests (Testcontainers
  — PostgreSQL and Kafka-compatible broker) for migrations, repository
  behavior, and idempotent Kafka consumption.
- [x] Instrumented through the existing Phase 4 observability stack
  (custom metrics, traces, structured logs); Prometheus scrape config,
  Grafana dashboard panels, and alert rules added for the new service.
- [x] Added to the Compose `app` profile with a multi-stage Docker
  build, non-root user, health checks, resource limits, and a
  localhost-only port; `Makefile` targets and an idempotent Phase 5
  smoke test (`correlation-smoke-test.sh`) added, none of which delete
  persistent volumes.
- [ ] End-to-end runtime verification (`make correlation-up`, full
  correlation smoke-test pass, a real ingestion cycle producing
  persisted evidence, a real correlation result reaching the incident
  service, Grafana panels rendering live data, Testcontainers
  integration tests actually executing) — **blocked**: Docker was not
  installed in the environment this phase was authored in. Static
  validation (Maven `verify` excluding Docker-dependent tests, YAML/
  JSON Schema parsing, and the Maven package build for both this
  service and the incident service) was completed and passes. This
  must be completed and confirmed before Phase 5 is considered fully
  done — see `services/telemetry-correlation-service/README.md`'s
  known-limitations section for the exact list.

## Phase 6 — Detection engine

**Goal:** Implement SLO evaluation and anomaly detection that raises
candidate incidents onto Redpanda.

**Acceptance criteria:**
- SLO definitions are configurable per monitored service.
- Detection logic has documented, tested behavior for at least
  latency, error-rate, and availability SLO types.
- Candidate incidents are published as well-defined events, with
  schema validation.
- Controlled failure testing demonstrates detection against injected
  faults.

## Phase 7 — Investigation agent and retrieval

**Goal:** Build the Python/LangGraph Investigation Agent, hybrid
retrieval over runbooks and historical incidents, and integration with
local Ollama inference.

**Acceptance criteria:**
- Agent consumes candidate incidents and produces a draft root-cause
  analysis backed by retrieved evidence and runbook citations.
- Hybrid retrieval (lexical + vector via pgvector) with reranking is
  implemented and evaluated against a test runbook set.
- All inference runs against local Ollama models by default; no paid
  API calls are required for the default configuration.

## Phase 8 — Human approval workflow and remediation recommendations

**Goal:** Implement the Human Approval Gate as a hard architectural
boundary, per [ADR 0004](decisions/0004-human-approved-remediation.md).

**Acceptance criteria:**
- No remediation action can execute without an authenticated,
  authorized approval recorded through Keycloak-backed identity.
- Approval and rejection are both fully audited.
- Failure of the approval or identity subsystem blocks remediation
  (fail closed), verified by controlled failure testing.

## Phase 9 — Operator dashboard and auditable reporting

**Goal:** Build the Next.js/React operator dashboard and the incident
report generator.

**Acceptance criteria:**
- Operators can view candidate incidents, evidence, and recommended
  remediation, and approve/reject through the dashboard.
- End-to-end flows are covered by Playwright tests.
- Generated incident reports are stored in MinIO and are retrievable
  and human-readable, with all claims traceable to underlying
  evidence.

## Phase 10 — Kubernetes packaging and optional AWS deployment mapping

**Goal:** Package the full stack for `kind`/Kubernetes via Helm, and
document (without provisioning) an optional AWS deployment mapping.

**Acceptance criteria:**
- The full stack deploys to a local `kind` cluster via Helm charts.
- k6 load tests and Trivy/CodeQL scans run in CI against the packaged
  services.
- AWS deployment mapping is documented per
  [system-overview.md](architecture/system-overview.md#8-local-and-optional-aws-deployment-mappings)
  but no AWS resources are provisioned by any repository tooling.
