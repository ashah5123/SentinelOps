-- Deployment history, consumed idempotently from deployment.changed.v1. See Deployment.java.
CREATE TABLE telemetry.deployments (
    id                          UUID PRIMARY KEY,
    deployment_id               VARCHAR(128) NOT NULL,
    service_name                VARCHAR(150) NOT NULL,
    version                     VARCHAR(128) NOT NULL,
    environment                 VARCHAR(50) NOT NULL,
    status                      VARCHAR(20) NOT NULL,
    started_at                  TIMESTAMPTZ NOT NULL,
    completed_at                TIMESTAMPTZ,
    source                      VARCHAR(100) NOT NULL,
    rollback_of_deployment_id   VARCHAR(128),
    source_event_id             VARCHAR(128) NOT NULL,
    correlation_id              VARCHAR(128),
    created_at                  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_deployments_source_event_id UNIQUE (source_event_id)
);

CREATE INDEX idx_deployments_service_started_at ON telemetry.deployments (service_name, started_at DESC);
