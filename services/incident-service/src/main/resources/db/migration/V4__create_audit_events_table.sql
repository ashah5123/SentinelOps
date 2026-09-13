-- Immutable audit trail. Append-only at the application layer: the service
-- exposes no update or delete operation against this table.
CREATE TABLE audit.audit_events (
    id              UUID PRIMARY KEY,
    incident_id     UUID,
    action          VARCHAR(100) NOT NULL,
    actor_type      VARCHAR(20) NOT NULL
                        CHECK (actor_type IN ('SYSTEM', 'LOCAL_USER', 'EVENT_CONSUMER')),
    actor_id        VARCHAR(100) NOT NULL,
    correlation_id  VARCHAR(64) NOT NULL,
    occurred_at     TIMESTAMPTZ NOT NULL,
    metadata        JSONB
);

COMMENT ON TABLE audit.audit_events IS
    'Immutable audit trail. Never store raw secrets in metadata.';

CREATE INDEX idx_audit_events_incident_id ON audit.audit_events (incident_id);
CREATE INDEX idx_audit_events_occurred_at ON audit.audit_events (occurred_at);
CREATE INDEX idx_audit_events_correlation_id ON audit.audit_events (correlation_id);
