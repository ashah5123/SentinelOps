-- Normalized evidence records (metrics, logs, traces, deployments, dependency changes).
-- See Evidence.java for field semantics; JSONB is used only for bounded supplementary
-- attributes, never for core searchable fields.
CREATE TABLE telemetry.evidence (
    id                  UUID PRIMARY KEY,
    evidence_type       VARCHAR(20) NOT NULL,
    source_system       VARCHAR(30) NOT NULL,
    source_service      VARCHAR(150) NOT NULL,
    observed_at         TIMESTAMPTZ NOT NULL,
    ingested_at         TIMESTAMPTZ NOT NULL,
    trace_id            VARCHAR(64),
    span_id             VARCHAR(32),
    correlation_id      VARCHAR(128),
    deployment_id       UUID REFERENCES telemetry.deployments (id),
    metric_name         VARCHAR(200),
    metric_value        DOUBLE PRECISION,
    severity            VARCHAR(20),
    summary             VARCHAR(500) NOT NULL,
    source_reference    VARCHAR(500) NOT NULL,
    attributes          JSONB,
    fingerprint         VARCHAR(128) NOT NULL,
    CONSTRAINT uq_evidence_fingerprint UNIQUE (fingerprint)
);

CREATE INDEX idx_evidence_type ON telemetry.evidence (evidence_type);
CREATE INDEX idx_evidence_source_service ON telemetry.evidence (source_service);
CREATE INDEX idx_evidence_observed_at ON telemetry.evidence (observed_at);
CREATE INDEX idx_evidence_trace_id ON telemetry.evidence (trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_evidence_correlation_id ON telemetry.evidence (correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX idx_evidence_deployment_id ON telemetry.evidence (deployment_id) WHERE deployment_id IS NOT NULL;
CREATE INDEX idx_evidence_service_observed_at ON telemetry.evidence (source_service, observed_at);
