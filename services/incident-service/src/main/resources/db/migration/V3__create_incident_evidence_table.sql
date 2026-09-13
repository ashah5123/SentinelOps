-- Evidence recorded against an incident during investigation.
CREATE TABLE incidents.incident_evidence (
    id                UUID PRIMARY KEY,
    incident_id       UUID NOT NULL REFERENCES incidents.incidents (id),
    evidence_type     VARCHAR(50) NOT NULL,
    description       TEXT NOT NULL,
    source_reference  VARCHAR(500),
    correlation_id    VARCHAR(64) NOT NULL,
    recorded_at       TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_incident_evidence_incident_id
    ON incidents.incident_evidence (incident_id);
