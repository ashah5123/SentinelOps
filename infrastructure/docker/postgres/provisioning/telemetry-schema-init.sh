#!/usr/bin/env bash
# Idempotent provisioning for the telemetry-correlation-service's PostgreSQL schema.
#
# Why this lives here and not in postgres/init/ (docker-entrypoint-initdb.d): those scripts
# only run once, automatically, against a completely empty $PGDATA — they never re-run against
# a volume that already exists from an earlier phase. Phase 5 introduces a service whose schema
# did not exist when most developers' local Postgres volumes were first initialized (Phase 2/3),
# so schema creation cannot depend on that one-time hook. Instead this script runs as its own
# one-shot Compose service (see "postgres-schema-init" in docker-compose.yml), every time the
# platform starts, the same way "redpanda-topics-init" and "minio-bucket-init" already handle
# idempotent provisioning against a possibly-pre-existing volume. Safe to run any number of
# times against a fresh or existing database.
set -euo pipefail

: "${POSTGRES_APP_USER:?POSTGRES_APP_USER must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS telemetry AUTHORIZATION ${POSTGRES_APP_USER};
    GRANT USAGE, CREATE ON SCHEMA telemetry TO ${POSTGRES_APP_USER};
EOSQL

echo "SentinelOps: schema 'telemetry' is ready (owned by ${POSTGRES_APP_USER})."
