-- Transactional outbox for incident.evidence.correlated.v1. See OutboxEvent.java and ADR 0007.
CREATE TABLE telemetry.outbox_events (
    id                  UUID PRIMARY KEY,
    aggregate_type      VARCHAR(50) NOT NULL,
    aggregate_id        UUID NOT NULL,
    topic               VARCHAR(150) NOT NULL,
    event_type          VARCHAR(100) NOT NULL,
    schema_version      INTEGER NOT NULL,
    payload             JSONB NOT NULL,
    correlation_id      VARCHAR(128) NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    published_at        TIMESTAMPTZ,
    attempt_count       INTEGER NOT NULL DEFAULT 0,
    next_attempt_at     TIMESTAMPTZ NOT NULL,
    last_error          VARCHAR(1000),
    status              VARCHAR(20) NOT NULL
);

CREATE INDEX idx_outbox_events_status_next_attempt ON telemetry.outbox_events (status, next_attempt_at);
