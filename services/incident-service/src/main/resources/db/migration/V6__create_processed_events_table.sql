-- Tracks previously-consumed inbound event IDs so redelivery under the
-- broker's at-least-once semantics does not cause duplicate domain effects.
-- See ADR 0008.
CREATE TABLE incidents.processed_events (
    id                UUID PRIMARY KEY,
    source_event_id   VARCHAR(128) NOT NULL,
    topic             VARCHAR(150) NOT NULL,
    processed_at      TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_processed_events_source_event_id UNIQUE (source_event_id)
);

CREATE INDEX idx_processed_events_topic ON incidents.processed_events (topic);
