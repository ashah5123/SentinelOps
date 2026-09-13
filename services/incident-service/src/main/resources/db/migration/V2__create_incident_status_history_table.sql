-- Append-only record of every incident status transition.
CREATE TABLE incidents.incident_status_history (
    id              UUID PRIMARY KEY,
    incident_id     UUID NOT NULL REFERENCES incidents.incidents (id),
    from_status     VARCHAR(20)
                        CHECK (from_status IN (
                            'DETECTED', 'INVESTIGATING', 'AWAITING_APPROVAL',
                            'MITIGATING', 'RESOLVED', 'FAILED'
                        )),
    to_status       VARCHAR(20) NOT NULL
                        CHECK (to_status IN (
                            'DETECTED', 'INVESTIGATING', 'AWAITING_APPROVAL',
                            'MITIGATING', 'RESOLVED', 'FAILED'
                        )),
    reason          VARCHAR(500),
    correlation_id  VARCHAR(64) NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_incident_status_history_incident_id
    ON incidents.incident_status_history (incident_id);
