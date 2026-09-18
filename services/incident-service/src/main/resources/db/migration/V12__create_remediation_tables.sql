-- Phase 14: policy-controlled automated remediation engine. Lives in the `incidents` schema
-- (full CRUD already granted) — remediation state is updated in place throughout its lifecycle,
-- not merely appended, like `agent_proposals` (Phase 13).

-- Versioned runbook definitions (validated YAML, stored alongside its parsed metadata for fast
-- listing/filtering without re-parsing). A (slug, version) pair is immutable once created — a
-- runbook change always creates a new version, never edits one in place, so an in-flight
-- execution's step list can never change underneath it.
CREATE TABLE incidents.remediation_runbooks (
    id                   UUID PRIMARY KEY,
    slug                 VARCHAR(100) NOT NULL,
    version              INT NOT NULL,
    title                VARCHAR(200) NOT NULL,
    risk_classification  VARCHAR(10) NOT NULL CHECK (risk_classification IN ('LOW', 'MEDIUM', 'HIGH')),
    definition_yaml      TEXT NOT NULL,
    definition_hash      VARCHAR(64) NOT NULL,
    step_count           INT NOT NULL,
    is_active            BOOLEAN NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ NOT NULL,
    created_by           VARCHAR(100) NOT NULL,

    CONSTRAINT uq_remediation_runbooks_slug_version UNIQUE (slug, version)
);

CREATE INDEX idx_remediation_runbooks_slug_active ON incidents.remediation_runbooks (slug, is_active);

-- One row per execution attempt of a runbook. The strict state machine (section: "Build the
-- remediation engine") is enforced entirely in application code (RemediationStateMachine) —
-- this table just records whichever state the machine most recently reached.
CREATE TABLE incidents.remediation_executions (
    id                   UUID PRIMARY KEY,
    runbook_id           UUID NOT NULL REFERENCES incidents.remediation_runbooks (id),
    incident_id          UUID REFERENCES incidents.incidents (id),
    proposal_id          UUID REFERENCES incidents.agent_proposals (id),
    idempotency_key      VARCHAR(150) NOT NULL,
    status               VARCHAR(20) NOT NULL DEFAULT 'PROPOSED'
                             CHECK (status IN (
                                 'PROPOSED', 'APPROVED', 'SCHEDULED', 'RUNNING', 'SUCCEEDED',
                                 'FAILED', 'ROLLED_BACK', 'CANCELLED', 'DENIED')),
    dry_run              BOOLEAN NOT NULL DEFAULT false,
    requested_by         VARCHAR(100) NOT NULL,
    correlation_id       VARCHAR(64) NOT NULL,
    parameters           JSONB NOT NULL DEFAULT '{}',
    blast_radius         JSONB NOT NULL DEFAULT '{}',
    policy_decision      VARCHAR(20) NOT NULL CHECK (policy_decision IN ('ALLOW', 'DENY', 'REQUIRE_APPROVAL')),
    policy_reason        VARCHAR(500) NOT NULL,
    policy_version       INT NOT NULL,
    required_approvals   INT NOT NULL DEFAULT 1,
    cancel_requested     BOOLEAN NOT NULL DEFAULT false,
    emergency_stop       BOOLEAN NOT NULL DEFAULT false,
    health_before        JSONB,
    health_after         JSONB,
    rollback_reason      VARCHAR(500),
    failure_reason       VARCHAR(500),
    created_at           TIMESTAMPTZ NOT NULL,
    scheduled_at         TIMESTAMPTZ,
    started_at           TIMESTAMPTZ,
    completed_at         TIMESTAMPTZ,
    rolled_back_at       TIMESTAMPTZ,

    CONSTRAINT uq_remediation_executions_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_remediation_executions_status ON incidents.remediation_executions (status);
CREATE INDEX idx_remediation_executions_incident_id ON incidents.remediation_executions (incident_id);

COMMENT ON COLUMN incidents.remediation_executions.emergency_stop IS
    'Set by an administrator to halt this execution (and refuse further step attempts) '
    'immediately, independent of cancel_requested''s normal cooperative-cancellation path.';

-- Every approval recorded against an execution. HIGH-risk runbooks require 2 distinct approvers
-- (required_approvals = 2 on the execution row) — enforced by counting distinct approver rows,
-- never by trusting a client-supplied count.
CREATE TABLE incidents.remediation_approvals (
    id            UUID PRIMARY KEY,
    execution_id  UUID NOT NULL REFERENCES incidents.remediation_executions (id),
    approver      VARCHAR(100) NOT NULL,
    approved_at   TIMESTAMPTZ NOT NULL,
    note          VARCHAR(1000),

    CONSTRAINT uq_remediation_approvals_execution_approver UNIQUE (execution_id, approver)
);

-- Step-level execution record: one row per (execution, step_index) attempt series. Retries
-- update the same row's attempt_count/status rather than inserting a new row, so "how many times
-- did step 2 retry" is a single row to read, not a join+count.
CREATE TABLE incidents.remediation_steps (
    id               UUID PRIMARY KEY,
    execution_id     UUID NOT NULL REFERENCES incidents.remediation_executions (id),
    step_index       INT NOT NULL,
    step_name        VARCHAR(100) NOT NULL,
    adapter_type     VARCHAR(50) NOT NULL,
    parameters       JSONB NOT NULL DEFAULT '{}',
    is_rollback_step BOOLEAN NOT NULL DEFAULT false,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    attempt_count    INT NOT NULL DEFAULT 0,
    started_at       TIMESTAMPTZ,
    completed_at     TIMESTAMPTZ,
    output           JSONB,
    error            VARCHAR(500),

    CONSTRAINT uq_remediation_steps_execution_index_rollback UNIQUE (execution_id, step_index, is_rollback_step)
);

-- A simple, portable distributed lock: one row per contended resource key (e.g.
-- "service:checkout-api|environment:production"), acquired with INSERT ... ON CONFLICT DO
-- NOTHING and released by DELETE — the same idempotent-insert idiom used throughout this
-- codebase (see AlertFingerprintRepository's ensureExists), rather than a new locking primitive.
CREATE TABLE incidents.remediation_locks (
    resource_key  VARCHAR(200) PRIMARY KEY,
    execution_id  UUID NOT NULL REFERENCES incidents.remediation_executions (id),
    acquired_at   TIMESTAMPTZ NOT NULL,
    expires_at    TIMESTAMPTZ NOT NULL
);

-- Local, in-database simulation of a Kubernetes Deployment (section: "Safe action adapters" —
-- "use mock or local containerized infrastructure for demonstrations"; no real cluster is
-- available in this environment). Models exactly the state the adapters below need: current
-- replica count, a bounded revision history for rollback, and a coarse health flag.
CREATE TABLE incidents.simulated_deployments (
    service       VARCHAR(100) NOT NULL,
    environment   VARCHAR(50) NOT NULL,
    replicas      INT NOT NULL DEFAULT 1,
    revision      INT NOT NULL DEFAULT 1,
    revision_history JSONB NOT NULL DEFAULT '[]',
    healthy       BOOLEAN NOT NULL DEFAULT true,
    restarted_at  TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (service, environment)
);

-- Local feature-flag store (a demonstration-scope substitute for a real flag service).
CREATE TABLE incidents.simulated_feature_flags (
    name        VARCHAR(100) PRIMARY KEY,
    enabled     BOOLEAN NOT NULL DEFAULT true,
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  VARCHAR(100)
);

-- Local worker-queue pause/resume state (a demonstration-scope substitute for a real queue).
CREATE TABLE incidents.simulated_queues (
    name        VARCHAR(100) PRIMARY KEY,
    paused      BOOLEAN NOT NULL DEFAULT false,
    updated_at  TIMESTAMPTZ NOT NULL,
    updated_by  VARCHAR(100)
);

-- Local bounded cache-namespace simulation (cleared entries are just deleted rows, not a real
-- Redis/Memcached instance, for the same "no external infra available" reason as above).
CREATE TABLE incidents.simulated_cache_entries (
    namespace   VARCHAR(100) NOT NULL,
    cache_key   VARCHAR(200) NOT NULL,
    value       VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (namespace, cache_key)
);
