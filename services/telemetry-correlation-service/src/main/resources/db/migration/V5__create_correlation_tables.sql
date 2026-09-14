-- One row per correlation run (correlation_results) plus the scored evidence it selected
-- (correlation_evidence). See CorrelationResult.java / CorrelationEvidence.java.
CREATE TABLE telemetry.correlation_results (
    id                  UUID PRIMARY KEY,
    incident_id         UUID NOT NULL,
    affected_service    VARCHAR(150) NOT NULL,
    detected_at         TIMESTAMPTZ NOT NULL,
    evaluated_at        TIMESTAMPTZ NOT NULL,
    evidence_count      INTEGER NOT NULL,
    source_event_id     VARCHAR(128) NOT NULL,
    correlation_id      VARCHAR(128),
    created_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_correlation_results_source_event_id UNIQUE (source_event_id)
);

CREATE INDEX idx_correlation_results_incident_id ON telemetry.correlation_results (incident_id);

CREATE TABLE telemetry.correlation_evidence (
    id                          UUID PRIMARY KEY,
    correlation_result_id       UUID NOT NULL REFERENCES telemetry.correlation_results (id),
    evidence_id                 UUID NOT NULL REFERENCES telemetry.evidence (id),
    score                       DOUBLE PRECISION NOT NULL,
    explanation                 VARCHAR(1000) NOT NULL,
    rank                        INTEGER NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_correlation_evidence_result_evidence UNIQUE (correlation_result_id, evidence_id)
);

CREATE INDEX idx_correlation_evidence_result_id ON telemetry.correlation_evidence (correlation_result_id);
