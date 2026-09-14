-- Idempotent-consumption tracking for inbound Kafka events. See ProcessedEvent.java.
CREATE TABLE telemetry.processed_events (
    id                  UUID PRIMARY KEY,
    source_event_id     VARCHAR(128) NOT NULL,
    topic               VARCHAR(150) NOT NULL,
    processed_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_processed_events_source_event_id UNIQUE (source_event_id)
);
