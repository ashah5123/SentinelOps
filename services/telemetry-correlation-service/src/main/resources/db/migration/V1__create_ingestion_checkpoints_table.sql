-- Ingestion watermark per (source, monitored service) pair. See IngestionCheckpoint.java.
CREATE TABLE telemetry.ingestion_checkpoints (
    id                  UUID PRIMARY KEY,
    source              VARCHAR(20) NOT NULL,
    monitored_service   VARCHAR(150) NOT NULL,
    watermark           TIMESTAMPTZ NOT NULL,
    last_run_at         TIMESTAMPTZ,
    last_run_status     VARCHAR(20),
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_ingestion_checkpoints_source_service UNIQUE (source, monitored_service)
);
