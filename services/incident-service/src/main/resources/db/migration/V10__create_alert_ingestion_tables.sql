-- Phase 12: external alert ingestion, deduplication, correlation, and notification routing.
-- The `alerts` schema was just created (see infrastructure/docker/postgres/init/03-schemas.sh);
-- this is its first migration. Unlike `audit` (append-only) this schema gets full CRUD, matching
-- `incidents` — escalations are cancelled in place and fingerprint occurrence counters are
-- updated, not just appended.

-- One durable row per accepted ingestion attempt (delivery idempotency lives here via
-- dedup_key). Never updated except to link the incident it was routed to — every other field is
-- the canonical alert exactly as validated at ingestion time.
CREATE TABLE alerts.alert_events (
    id                 UUID PRIMARY KEY,
    connector_type     VARCHAR(30) NOT NULL,
    source             VARCHAR(100) NOT NULL,
    external_id        VARCHAR(200),
    fingerprint        VARCHAR(64) NOT NULL,
    fingerprint_version INT NOT NULL,
    status             VARCHAR(10) NOT NULL CHECK (status IN ('FIRING', 'RESOLVED')),
    alert_name         VARCHAR(200) NOT NULL,
    summary            VARCHAR(500),
    description        VARCHAR(2000),
    severity           VARCHAR(10),
    service            VARCHAR(100),
    environment        VARCHAR(50),
    region             VARCHAR(50),
    labels             JSONB NOT NULL DEFAULT '{}',
    annotations        JSONB NOT NULL DEFAULT '{}',
    source_timestamp   TIMESTAMPTZ NOT NULL,
    ingested_at        TIMESTAMPTZ NOT NULL,
    generator_url      VARCHAR(500),
    schema_version     INT NOT NULL,
    raw_payload_hash   VARCHAR(64) NOT NULL,
    -- Delivery-idempotency key: sha256(connectorType|source|externalIdOrFingerprint|status|
    -- sourceTimestamp) — see AlertIngestionService. A UNIQUE violation on retry of the exact same
    -- delivery is caught and treated as an idempotent replay, never a duplicate row.
    dedup_key          VARCHAR(64) NOT NULL,
    correlation_id     VARCHAR(64) NOT NULL,
    incident_id        UUID REFERENCES incidents.incidents (id),
    created_at         TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_alert_events_dedup_key UNIQUE (dedup_key)
);

CREATE INDEX idx_alert_events_fingerprint ON alerts.alert_events (fingerprint);
CREATE INDEX idx_alert_events_incident_id ON alerts.alert_events (incident_id);
CREATE INDEX idx_alert_events_source ON alerts.alert_events (source, external_id);

COMMENT ON COLUMN alerts.alert_events.dedup_key IS
    'Delivery-idempotency key. A retry of literally the same webhook delivery collides on this '
    'unique key rather than creating a second row — see AlertIngestionService.';

-- Semantic-deduplication and occurrence state, one row per fingerprint. Updated atomically
-- (INSERT ... ON CONFLICT (fingerprint) DO UPDATE ... RETURNING) so concurrent deliveries of the
-- same fingerprint can never both observe "first occurrence" and both create an incident.
CREATE TABLE alerts.alert_fingerprints (
    fingerprint         VARCHAR(64) PRIMARY KEY,
    fingerprint_version INT NOT NULL,
    source              VARCHAR(100) NOT NULL,
    first_seen_at       TIMESTAMPTZ NOT NULL,
    last_seen_at        TIMESTAMPTZ NOT NULL,
    occurrence_count    INT NOT NULL DEFAULT 1,
    active_incident_id  UUID REFERENCES incidents.incidents (id),
    status              VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'RESOLVED')),
    updated_at          TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE alerts.alert_fingerprints IS
    'Semantic-dedup state: repeated firing alerts sharing a fingerprint increment '
    'occurrence_count and update last_seen_at instead of creating a new incident, as long as '
    'active_incident_id is set and status = ACTIVE. See docs/development/alert-ingestion.md''s '
    'deduplication-window section for what happens after the configured window elapses.';

-- Every deterministic correlation decision (section 8) — kept even when the decision is "no
-- match, create a new incident" is NOT recorded here (only actual attachments are), so this table
-- only ever grows with real correlation events, not every ingestion attempt.
CREATE TABLE alerts.alert_correlations (
    id              UUID PRIMARY KEY,
    alert_event_id  UUID NOT NULL REFERENCES alerts.alert_events (id),
    incident_id     UUID NOT NULL REFERENCES incidents.incidents (id),
    rule_id         VARCHAR(100) NOT NULL,
    rule_version    INT NOT NULL,
    matched_fields  JSONB NOT NULL,
    explanation     VARCHAR(500) NOT NULL,
    correlated_at   TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_alert_correlations_incident_id ON alerts.alert_correlations (incident_id);
CREATE INDEX idx_alert_correlations_alert_event_id ON alerts.alert_correlations (alert_event_id);

-- Outbound notifications, dispatched through the same durable-then-async pattern as the
-- transactional outbox (see NotificationDispatcher). idempotency_key + channel is unique so the
-- same routing decision for the same alert event never enqueues the same notification twice.
CREATE TABLE alerts.notifications (
    id                    UUID PRIMARY KEY,
    incident_id           UUID NOT NULL REFERENCES incidents.incidents (id),
    channel               VARCHAR(20) NOT NULL CHECK (channel IN ('EMAIL', 'WEBHOOK', 'IN_APP')),
    routing_rule_id       VARCHAR(100) NOT NULL,
    routing_rule_version  INT NOT NULL,
    idempotency_key       VARCHAR(150) NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING'
                              CHECK (status IN ('PENDING', 'SENT', 'FAILED', 'DEAD_LETTERED')),
    attempt_count         INT NOT NULL DEFAULT 0,
    last_error            VARCHAR(500),
    payload               JSONB NOT NULL,
    created_at            TIMESTAMPTZ NOT NULL,
    sent_at               TIMESTAMPTZ,
    next_attempt_at       TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_notifications_idempotency_key_channel UNIQUE (idempotency_key, channel)
);

CREATE INDEX idx_notifications_incident_id ON alerts.notifications (incident_id);
CREATE INDEX idx_notifications_dispatch_queue ON alerts.notifications (status, next_attempt_at);

COMMENT ON COLUMN alerts.notifications.payload IS
    'Only the safe, rendered fields a human notification needs (incident id/severity/service/'
    'environment/summary/link/routing reason) — never a secret or the raw alert payload. See '
    'NotificationRenderer.';

-- Escalation schedule. One row per (incident, routing_rule) so re-evaluating routing for the same
-- incident never schedules a second, duplicate escalation for the same rule.
CREATE TABLE alerts.escalations (
    id                  UUID PRIMARY KEY,
    incident_id         UUID NOT NULL REFERENCES incidents.incidents (id),
    routing_rule_id     VARCHAR(100) NOT NULL,
    routing_rule_version INT NOT NULL,
    scheduled_at        TIMESTAMPTZ NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED'
                            CHECK (status IN ('SCHEDULED', 'DELIVERED', 'CANCELLED')),
    delivered_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    cancelled_reason    VARCHAR(200),
    created_at          TIMESTAMPTZ NOT NULL,

    CONSTRAINT uq_escalations_incident_rule UNIQUE (incident_id, routing_rule_id)
);

CREATE INDEX idx_escalations_due ON alerts.escalations (status, scheduled_at);
