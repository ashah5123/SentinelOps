-- Transactional outbox. Domain writes and the corresponding outbox row are
-- committed in the same database transaction; a background publisher claims
-- and publishes PENDING rows. See ADR 0007.
CREATE TABLE incidents.outbox_events (
    id               UUID PRIMARY KEY,
    aggregate_type   VARCHAR(50) NOT NULL,
    aggregate_id     UUID NOT NULL,
    topic            VARCHAR(150) NOT NULL,
    event_type       VARCHAR(100) NOT NULL,
    schema_version   INT NOT NULL,
    payload          JSONB NOT NULL,
    correlation_id   VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL,
    published_at     TIMESTAMPTZ,
    attempt_count    INT NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ NOT NULL,
    last_error       VARCHAR(1000),
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                         CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED'))
);

-- Supports the publisher's claim query: PENDING rows due for another attempt,
-- oldest first.
CREATE INDEX idx_outbox_events_publish_queue
    ON incidents.outbox_events (status, next_attempt_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_outbox_events_aggregate_id ON incidents.outbox_events (aggregate_id);
