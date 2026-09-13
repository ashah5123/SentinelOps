# SentinelOps Roadmap

Each phase below is scoped to be small and independently testable.
A phase is not considered complete until its acceptance criteria are
met. This roadmap will be revised as phases complete and new
information emerges.

## Phase 1 — Foundation (current)

**Goal:** Establish repository structure, architecture, and
development standards. No application code.

**Acceptance criteria:**
- Repository initialized with `main` as the default branch, correct
  local author configuration, and no secrets committed.
- `.gitignore`, `.editorconfig`, `.gitattributes`, `.env.example`,
  `LICENSE`, `CONTRIBUTING.md`, `SECURITY.md`, and `README.md` exist
  and accurately describe the current (foundation-only) state.
- Architecture is documented (`docs/architecture/system-overview.md`)
  and at least the initial set of ADRs are recorded.
- `make doctor` accurately reports the presence/absence of required
  local tooling without installing or modifying anything.
- `make validate` passes for the checked-in state of the repository.

## Phase 2 — Local infrastructure baseline

**Goal:** Stand up the local, zero-cost infrastructure stack via
Docker Compose, with no application services yet.

**Acceptance criteria:**
- `docker compose up` brings up PostgreSQL (with pgvector extension),
  Redis, MinIO, Redpanda, and Keycloak, all reachable on documented
  local ports.
- Each service has a working health check.
- Configuration is sourced from `.env`, derived from `.env.example`.
- Bringing the stack up and down is documented and reproducible.

## Phase 3 — Observability baseline

**Goal:** Stand up the OpenTelemetry Collector, Prometheus, Grafana,
Loki, Tempo, and Alertmanager locally, with a minimal instrumented
"hello world" Java and Python service to prove the pipeline end to end.

**Acceptance criteria:**
- A sample Java service and a sample Python service both emit metrics,
  logs, and traces via OpenTelemetry.
- Telemetry is visible in Grafana, sourced from Prometheus, Loki, and
  Tempo, with trace-to-log correlation demonstrated.
- The sample services and dashboards are removed or clearly marked as
  throwaway scaffolding once the pipeline is proven, per this
  project's "no placeholder services" principle — or are promoted into
  Phase 4 if they form real groundwork.

## Phase 4 — Telemetry ingestion and correlation service

**Goal:** Build the real Ingestion & Correlation Service (Java) that
normalizes telemetry, deployment events, and dependency metadata into
a shared incident-evidence model in PostgreSQL.

**Acceptance criteria:**
- Service ingests from Prometheus/Loki/Tempo and a deployment-event
  source, and persists normalized evidence records.
- Unit tests (JUnit) and integration tests (Testcontainers) cover the
  ingestion and normalization logic.
- API contract is documented (OpenAPI) and covered by contract tests.

## Phase 5 — Detection engine

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

## Phase 6 — Investigation agent and retrieval

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

## Phase 7 — Human approval workflow and remediation recommendations

**Goal:** Implement the Human Approval Gate as a hard architectural
boundary, per [ADR 0004](decisions/0004-human-approved-remediation.md).

**Acceptance criteria:**
- No remediation action can execute without an authenticated,
  authorized approval recorded through Keycloak-backed identity.
- Approval and rejection are both fully audited.
- Failure of the approval or identity subsystem blocks remediation
  (fail closed), verified by controlled failure testing.

## Phase 8 — Operator dashboard and auditable reporting

**Goal:** Build the Next.js/React operator dashboard and the incident
report generator.

**Acceptance criteria:**
- Operators can view candidate incidents, evidence, and recommended
  remediation, and approve/reject through the dashboard.
- End-to-end flows are covered by Playwright tests.
- Generated incident reports are stored in MinIO and are retrievable
  and human-readable, with all claims traceable to underlying
  evidence.

## Phase 9 — Kubernetes packaging and optional AWS deployment mapping

**Goal:** Package the full stack for `kind`/Kubernetes via Helm, and
document (without provisioning) an optional AWS deployment mapping.

**Acceptance criteria:**
- The full stack deploys to a local `kind` cluster via Helm charts.
- k6 load tests and Trivy/CodeQL scans run in CI against the packaged
  services.
- AWS deployment mapping is documented per
  [system-overview.md](architecture/system-overview.md#8-local-and-optional-aws-deployment-mappings)
  but no AWS resources are provisioned by any repository tooling.
