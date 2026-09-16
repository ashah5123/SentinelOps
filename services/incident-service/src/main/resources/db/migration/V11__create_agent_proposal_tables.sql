-- Phase 13: MCP-exposed, approval-gated agent operations. A proposal is never executed by the
-- MCP server itself — see AgentProposalService/AgentApprovalService. Lives in the `incidents`
-- schema (full CRUD already granted) since, unlike `audit`, a proposal's review fields are
-- updated in place (approved/rejected/executed), not merely appended.

CREATE TABLE incidents.agent_proposals (
    id                   UUID PRIMARY KEY,
    incident_id          UUID NOT NULL REFERENCES incidents.incidents (id),
    action_type          VARCHAR(30) NOT NULL
                             CHECK (action_type IN (
                                 'ACKNOWLEDGE', 'ASSIGN', 'CHANGE_SEVERITY', 'ADD_NOTE',
                                 'ESCALATE', 'RESOLVE', 'REPLAY_DEAD_LETTER')),
    parameters           JSONB NOT NULL,
    reason               VARCHAR(1000) NOT NULL,
    evidence_references  JSONB NOT NULL DEFAULT '[]',
    expected_version     BIGINT NOT NULL,
    -- SHA-256 of (incidentId|actionType|parameters|expectedVersion) computed at creation time —
    -- re-verified byte-for-byte before execution so a proposal can never be executed with
    -- different parameters than a human actually reviewed and approved.
    content_hash         VARCHAR(64) NOT NULL,
    risk_classification  VARCHAR(10) NOT NULL CHECK (risk_classification IN ('LOW', 'MEDIUM', 'HIGH')),
    requested_by         VARCHAR(100) NOT NULL,
    requested_actor_type VARCHAR(20) NOT NULL,
    correlation_id       VARCHAR(64) NOT NULL,
    idempotency_key      VARCHAR(150) NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL,
    expires_at           TIMESTAMPTZ NOT NULL,

    status               VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                             CHECK (status IN (
                                 'PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'EXECUTED',
                                 'EXECUTION_FAILED')),

    approved_by          VARCHAR(100),
    approved_at          TIMESTAMPTZ,
    review_note          VARCHAR(1000),
    approval_expires_at  TIMESTAMPTZ,
    -- One-time execution: flips to true the instant execution begins, inside the same
    -- transaction that verifies it was false — see AgentApprovalService#execute. A second,
    -- concurrent execution attempt (or a replay) can never observe it still false.
    approval_consumed    BOOLEAN NOT NULL DEFAULT false,

    rejected_by          VARCHAR(100),
    rejected_at          TIMESTAMPTZ,
    rejection_note       VARCHAR(1000),

    executed_at          TIMESTAMPTZ,
    execution_result     VARCHAR(500),
    execution_error      VARCHAR(500),

    CONSTRAINT uq_agent_proposals_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_agent_proposals_incident_id ON incidents.agent_proposals (incident_id);
CREATE INDEX idx_agent_proposals_status ON incidents.agent_proposals (status);

COMMENT ON COLUMN incidents.agent_proposals.content_hash IS
    'Defends against a hypothetical altered-parameters replay: execution recomputes this hash '
    'from the stored row and refuses to proceed on a mismatch.';
COMMENT ON COLUMN incidents.agent_proposals.approval_consumed IS
    'Set true, atomically, the instant execution begins — makes approval one-time-use even under '
    'concurrent execution attempts (see the UPDATE ... WHERE approval_consumed = false pattern).';
