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

## Phase 5 — Telemetry ingestion and correlation service

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

## Phase 6 (reliability hardening) — Production reliability and failure recovery

**Note on numbering:** this is a cross-cutting reliability-hardening pass applied to the
already-completed incident service (Phase 3) and telemetry-correlation service (Phase 5) — it
does not build new user-facing capability and is tracked separately from the sequentially
numbered roadmap phase below also named "Phase 6" ("Detection engine"), which remains unstarted
future work. Every other reference to "Phase 6" in this repository's documentation means *this*
reliability-hardening work unless it explicitly says "Detection engine."

**Goal:** Harden both existing Java services so incident/telemetry events are processed reliably
across duplicate delivery, temporary dependency failures, application restarts, and
message-broker interruptions — see
[ADR 0011](decisions/0011-reliability-and-failure-recovery.md) and
`docs/development/reliability.md`.

**Acceptance criteria:**
- [x] The transactional outbox no longer holds a database transaction open across the Kafka
  network send: claiming, sending, and finalizing are three separate short operations, with a
  time-based lease (not a held row lock) protecting a claimed row from re-claiming (ADR 0011).
- [x] Outbox and inbound-consumer retry backoff both use jittered exponential backoff
  (`JitteredExponentialBackOff`), configurable per service.
- [x] Non-retryable exceptions (malformed JSON, invalid enum/validation values) are routed
  straight to the dead-letter topic without consuming retry attempts.
- [x] `/actuator/health/readiness` on both services fails when PostgreSQL or the Kafka-compatible
  broker is unreachable (previously it reflected only the application's own readiness state).
- [x] A configurable retention/cleanup job exists for `processed_events` idempotency records on
  both services.
- [x] New metrics: outbox backlog size, oldest-unpublished-event age, outbox dead-lettered
  events, consumer dead-lettered events, consumer retry attempts — plus a fixed gap closed
  (`incident.evidence.correlated.v1` consumption now also records a duplicate/processed metric).
  New Grafana dashboard ("SentinelOps Reliability") and Prometheus alert group
  (`sentinelops-reliability`) built on them.
- [x] Ten new Testcontainers-based integration tests covering: transactional
  commit-together/rollback-together, a pending event publishing successfully, a temporary
  consumer failure being retried, a pre-existing pending row surviving a simulated restart, a
  broker outage leaving events recoverable and resuming automatically once it ends, and
  readiness correctly failing/recovering around a PostgreSQL outage — added without modifying
  any existing test's assertions.
- [x] A local, free, reproducible fault-testing workflow
  (`infrastructure/docker/scripts/reliability-fault-test.sh`, `make reliability-test`) covering
  duplicate delivery, broker interruption/recovery, app restart, invalid events, and retry
  exhaustion.
- [ ] End-to-end runtime verification (the ten new integration tests actually executing against
  real PostgreSQL/Kafka-compatible containers; running `make reliability-test` against a live
  platform and recording real observations) — **blocked**: Docker was not installed in the
  environment this phase was authored in. Static validation (compile, spotless, the full
  non-Docker unit test suite for both services, `mvn verify` package builds, and YAML/JSON config
  parsing) was completed and passes — see `docs/benchmarks/phase-6-reliability.md` for the exact
  results and what remains unverified. This must be completed and confirmed before this Phase 6
  (reliability hardening) is considered fully done.

## Phase 7 (authentication, authorization, and audit logging) — Identity and access control for the incident service

**Note on numbering:** like Phase 6 (reliability hardening) above, this is a cross-cutting
security-hardening pass applied to the already-completed incident service (Phase 3) — it is
tracked separately from the sequentially numbered "Phase 7" below ("Investigation agent and
retrieval"), which remains unstarted future work. Every other reference to "Phase 7" in this
repository's documentation means *this* security-hardening work unless it explicitly says
"Investigation agent."

**Goal:** Make the incident service require authentication, enforce server-side role-based
authorization (`VIEWER`/`RESPONDER`/`ADMIN`), and record a reliable, admin-queryable audit trail —
see [`docs/development/security.md`](development/security.md).

**Acceptance criteria:**
- [x] A local Keycloak realm (`infrastructure/docker/keycloak/realm-export.json`), imported
  automatically on Compose startup, defines the `VIEWER`/`RESPONDER`/`ADMIN` realm roles, the
  `sentinelops-api` client (with an audience mapper), and three synthetic demo users — all
  bound to `127.0.0.1` only.
- [x] The incident service is an OAuth 2.0 resource server (`spring-boot-starter-oauth2-resource-server`)
  validating token signature, issuer, audience, and expiry on every request
  (`SecurityConfig`, `JwtAudienceValidator`); missing, malformed, wrong-issuer, wrong-audience,
  and expired tokens are all rejected with `401`.
- [x] Every incident-management endpoint is annotated with an explicit `@PreAuthorize` role rule;
  actor identity for every audit record and domain action comes only from the validated token's
  `sub` claim (`AuthenticatedActor`) — no request field can set or override it, and existing
  domain-transition validation (`IncidentTransitions`) is unchanged.
- [x] A new admin-only, paginated, bounded-filter global audit endpoint
  (`GET /api/v1/admin/audit-events`) and a bounded manual dead-letter-replay endpoint
  (`POST /api/v1/admin/dead-letter-topics/{topic}/replay`, restricted to the two `.dlq` topics
  this service consumes from) reuse the existing Phase 3 audit/outbox and Phase 6 dead-letter
  infrastructure rather than introducing new mechanisms.
- [x] Authorization denials are audited independently of the triggering request's own transaction
  (`DeniedActionAuditService`, `REQUIRES_NEW`); audit persistence failures increment a dedicated
  metric rather than failing silently.
- [x] New bounded-cardinality metrics (`sentinelops.auth.authentication_failures`,
  `sentinelops.auth.authorization_denials`, `sentinelops.audit.persistence_failures`) — none
  tagged by user ID, incident ID, or raw request path.
- [x] `/actuator/prometheus` requires HTTP Basic auth (a local static credential) rather than
  being anonymous; `/actuator/health(/**)` and `/actuator/info` remain public (required for the
  container health check); every other Actuator and documentation endpoint requires
  authentication. CORS is explicit and closed by default (`CORS_ALLOWED_ORIGINS`); CSRF is
  deliberately disabled with its rationale documented (stateless bearer-token API, no cookies).
- [x] Unit tests for the audience/role-mapping logic; a `MockMvc` + Spring Security test-support
  role-permission-matrix suite (allowed/forbidden per role, actor-identity-override attempts,
  invalid-transition-still-rejected, per-incident audit endpoint now admin-only); an
  embedded-Kafka test for dead-letter replay; and a real local-Keycloak integration test suite
  (`TokenValidationIntegrationTest`) covering missing/malformed/tampered/expired tokens and
  wrong-issuer/wrong-audience tokens minted by real, separate Keycloak clients/realms — no
  authentication is mocked in that suite.
- [x] All pre-existing Phase 3/4/5/6 unit tests continue to pass unmodified in assertions (only
  updated to attach the auth headers now required); no existing test's assertions were weakened.
- [ ] End-to-end runtime verification (the Keycloak-backed integration/role-matrix/API tests
  actually executing against a real Keycloak + PostgreSQL + Kafka-compatible broker, `make
  incident-up` bringing up Keycloak healthy, and a live demo walking through viewer/responder/
  admin behavior) — **blocked**: Docker was not installed in the environment this phase was
  authored in, consistent with every prior phase's runtime-verification gap. Static validation
  (compile, the full non-Docker/non-Testcontainers unit test suite — including a real
  embedded-Kafka dead-letter-replay test, which does not require Docker — and Compose/JSON/YAML
  config parsing) was completed and passes; see the phase completion report for exact numbers.
  This must be completed and confirmed before this Phase 7 (authentication, authorization, and
  audit logging) is considered fully done.

## Phase 8 (performance testing and reproducible benchmarks) — Load-test harness for the incident service

**Note on numbering:** like Phase 6 (reliability hardening) and Phase 7 (authentication,
authorization, and audit logging) above, this is a cross-cutting measurement pass applied to the
already-completed incident service — tracked separately from the sequentially numbered "Phase 8"
below ("Human approval workflow and remediation recommendations"), which remains unstarted future
work.

**Goal:** Build a reproducible, local, free/open-source load-testing harness (k6, via its own
Docker image) for the incident service's real endpoints, establish a measured baseline, and make
one evidence-backed optimization if the evidence supports it — see
[`docs/development/performance.md`](development/performance.md) and
[`docs/benchmarks/phase-8-performance.md`](../benchmarks/phase-8-performance.md).

**Acceptance criteria:**
- [x] Deterministic, isolated synthetic data seeding (`benchmark-seed.sh`, fixed random seed,
  every record tagged with a `bench-<runId>` `affectedService` marker) and scoped cleanup
  (`benchmark-cleanup.sh`) that only ever deletes rows matching that marker.
- [x] k6 scenarios for paginated reads, incident creation (with downstream outbox/event
  processing measured separately from HTTP acceptance latency), valid lifecycle transitions
  (each iteration owns a freshly created incident — no cross-VU transition conflicts), a mixed
  read/write workload, a bounded burst-and-recovery scenario, and a scenario dedicated to
  intentional unauthorized-request checks (kept separate from every performance number). Every
  scenario authenticates for real against the local Keycloak realm added in Phase 7 — no mocked
  or disabled authentication, no hardcoded credentials.
- [x] An orchestrator (`benchmark-run.sh`) that captures git revision/dirty state, host/Docker
  resource limits, pinned image versions, and relevant configuration per run, and samples
  incident-service's existing `/actuator/prometheus` (outbox backlog/oldest-pending-age/dead-
  letter/retry counters, HikariCP pool usage, process CPU, JVM heap, and the Phase 7 auth/audit
  failure counters) before, during, and after each run — reusing existing instrumentation rather
  than adding new metrics.
- [x] A regression this phase found via static review: Phase 7's mandatory authentication broke
  three pre-existing local scripts (`reliability-fault-test.sh`, `correlation-smoke-test.sh`,
  `observability-smoke-test.sh`) that called incident-service without a bearer token or the
  `/actuator/prometheus` Basic-auth credentials; all three were fixed (a shared
  `scripts/lib/auth.sh` helper), along with two unrelated pre-existing missing-`detectedAt`
  payload bugs found in the same review.
- [x] CI wiring: a short, bounded functional smoke benchmark runs on every push/PR; heavier
  scenarios are opt-in only (`workflow_dispatch`), not gating merges on shared-runner timing
  variance.
- [ ] An actual measured baseline, an evidence-backed optimization (or an explicit finding that
  none was identified), and a before/after comparison — **blocked**: Docker (and therefore k6,
  Postgres, Redpanda, and Keycloak) was not installed in the environment this phase was authored
  in, and the environment's owner asked not to install anything to conserve disk space. Every
  scenario, script, and CI job was built and statically validated (shell/JS syntax, YAML/JSON
  parsing, and the metrics-parsing/summary-generation logic unit-verified against hand-written
  fake input) but **never actually run**. No benchmark number in this repository's documentation
  is a real measurement. This must be executed — following the exact commands in
  `docs/development/performance.md` — before this Phase 8 (performance testing) is considered
  fully done.

## Phase 9 (production readiness, CI/CD, backup, and release recovery) — current

**Note on numbering:** like Phases 6-8 above, this is a cross-cutting hardening pass applied to
the already-completed incident service (and, for static analysis, the telemetry-correlation
service) — tracked separately from the sequentially numbered "Phase 9" below ("Operator dashboard
and auditable reporting"), which remains unstarted future work.

**Goal:** Make SentinelOps safely buildable, deployable, recoverable, and maintainable — see
[`docs/development/operations.md`](development/operations.md).

**Acceptance criteria:**
- [x] CI (`ci.yml`) hardened: `concurrency: cancel-in-progress`, per-job `timeout-minutes`,
  least-privilege `permissions` on every job, a new `static-analysis` job (SpotBugs, both
  services), a new `docker-build` job, new `vulnerability-scan` (Trivy, dependency + image,
  documented `ignore-unfixed` severity policy) and `sbom` (Syft/CycloneDX) jobs, and a new
  `backup-restore-verify` job. No job requires a repository secret or a paid service.
- [x] `spotbugs-maven-plugin` added to both services; the only findings on first run were
  Spring-constructor-injection false positives (`EI_EXPOSE_REP`/`EI_EXPOSE_REP2`, documented and
  excluded) plus one real, fixed defect (`DCN_NULLPOINTER_EXCEPTION` — catching `NullPointerException`
  instead of a null check in `TempoNormalizer`).
- [x] Migration release-safety reviewed: every migration to date is purely additive (documented
  table in operations.md); a new `MigrationUpgradePathTest` proves the N-1 -> N upgrade path
  preserves existing data and that the application's own non-superuser role can use the newly
  added table immediately.
- [x] PostgreSQL backup/restore tooling (`db-backup.sh`, `db-restore.sh`,
  `db-restore-verify.sh`) — explicit target database required, restore over the primary
  database refused, interactive confirmation, and a restore-verification workflow that proves a
  backup is actually restorable (not just that `pg_dump` exited zero) via a disposable database.
- [x] `integrity-check.sh` — bounded, read-only, exits non-zero on violation, never prints
  sensitive row contents; no tenancy check (none exists in this codebase).
- [x] Documented release/rollback procedure distinguishing application rollback, database
  restore, forward repair, and event replay, with rollback decision criteria and an explicit
  statement that database restore is never automatically safe; a local `release-rehearsal.sh`
  demonstrates the full procedure including a simulated failed release and an application
  rollback.
- [x] `ProductionSafetyCheck`: fails incident-service startup with a clear, itemized message when
  `ENVIRONMENT=production` and a credential/CORS/issuer/log-level setting still holds a
  development-only value; does nothing outside a declared production environment.
- [ ] Actual execution of every Docker-dependent check (CI jobs beyond `validate`/
  `static-analysis`, `make release-rehearsal`, real backup/restore/integrity numbers, Docker
  image sizes, Trivy/SBOM output) — **blocked**: Docker is not installed in the environment this
  phase was authored in, and installing it was explicitly declined to conserve local disk space.
  Every script, test, and CI job was written and statically validated (shell syntax, YAML
  parsing, and every non-Docker-dependent unit test — including `ProductionSafetyCheckTest` and
  both services' full non-Docker test suites, 70/70 and 49/49 passing respectively) but never
  executed end to end. This must be run — following the exact commands in
  `docs/development/operations.md` — before this Phase 9 is considered fully done.

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
