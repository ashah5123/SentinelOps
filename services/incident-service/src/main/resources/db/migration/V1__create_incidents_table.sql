-- Incident aggregate root. All timestamps are stored as UTC (timestamptz).
CREATE TABLE incidents.incidents (
    id                UUID PRIMARY KEY,
    incident_number   VARCHAR(32) NOT NULL,
    title             VARCHAR(200) NOT NULL,
    description       TEXT,
    severity          VARCHAR(10) NOT NULL
                          CHECK (severity IN ('SEV1', 'SEV2', 'SEV3', 'SEV4')),
    status            VARCHAR(20) NOT NULL
                          CHECK (status IN (
                              'DETECTED', 'INVESTIGATING', 'AWAITING_APPROVAL',
                              'MITIGATING', 'RESOLVED', 'FAILED'
                          )),
    source            VARCHAR(100) NOT NULL,
    affected_service  VARCHAR(100) NOT NULL,
    detected_at       TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL,
    resolved_at       TIMESTAMPTZ,
    correlation_id    VARCHAR(64) NOT NULL,
    -- Nullable: only present for incidents created from a consumed anomaly event.
    -- Unique when present, enforced below, so duplicate anomaly delivery cannot
    -- create duplicate incidents.
    source_event_id   VARCHAR(128),
    version           BIGINT NOT NULL DEFAULT 0,

    CONSTRAINT uq_incidents_incident_number UNIQUE (incident_number)
);

COMMENT ON TABLE incidents.incidents IS
    'Incident aggregate root. Status transitions are enforced application-side; '
    'this table never accepts an arbitrary status value directly from a client.';
COMMENT ON COLUMN incidents.incidents.source_event_id IS
    'Originating telemetry.anomaly.v1 event ID, when this incident was created '
    'from a consumed anomaly event. Unique to make anomaly consumption idempotent.';

-- Partial unique index: only enforce uniqueness where a source event actually exists.
CREATE UNIQUE INDEX uq_incidents_source_event_id
    ON incidents.incidents (source_event_id)
    WHERE source_event_id IS NOT NULL;

CREATE INDEX idx_incidents_status ON incidents.incidents (status);
CREATE INDEX idx_incidents_severity ON incidents.incidents (severity);
CREATE INDEX idx_incidents_affected_service ON incidents.incidents (affected_service);
CREATE INDEX idx_incidents_detected_at ON incidents.incidents (detected_at);
