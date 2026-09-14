-- Current service-dependency graph (service_dependencies) plus its append-only change history
-- (service_dependency_history), consumed idempotently from service.dependency.changed.v1.
CREATE TABLE telemetry.service_dependencies (
    id                  UUID PRIMARY KEY,
    source_service      VARCHAR(150) NOT NULL,
    target_service      VARCHAR(150) NOT NULL,
    dependency_type     VARCHAR(50) NOT NULL,
    environment         VARCHAR(50) NOT NULL,
    effective_at        TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_service_dependencies_edge
        UNIQUE (source_service, target_service, dependency_type, environment),
    CONSTRAINT chk_service_dependencies_not_self
        CHECK (source_service <> target_service)
);

CREATE INDEX idx_service_dependencies_source ON telemetry.service_dependencies (source_service);
CREATE INDEX idx_service_dependencies_target ON telemetry.service_dependencies (target_service);

CREATE TABLE telemetry.service_dependency_history (
    id                  UUID PRIMARY KEY,
    source_service      VARCHAR(150) NOT NULL,
    target_service      VARCHAR(150) NOT NULL,
    dependency_type     VARCHAR(50) NOT NULL,
    environment         VARCHAR(50) NOT NULL,
    operation           VARCHAR(20) NOT NULL,
    effective_at        TIMESTAMPTZ NOT NULL,
    source_event_id     VARCHAR(128) NOT NULL,
    recorded_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_service_dependency_history_source_event_id UNIQUE (source_event_id)
);

CREATE INDEX idx_service_dependency_history_source ON telemetry.service_dependency_history (source_service, effective_at DESC);
