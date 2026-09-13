#!/usr/bin/env bash
# Creates logical schemas for future SentinelOps services and grants the
# non-superuser application role usage of them. No application tables are
# created here — table ownership belongs to the future services that will
# run migrations against these schemas.
#
# Runs once, automatically, on first container initialization. Uses
# IF NOT EXISTS / DEFAULT PRIVILEGES so it is also safe to re-run manually.
set -euo pipefail

: "${POSTGRES_APP_USER:?POSTGRES_APP_USER must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE SCHEMA IF NOT EXISTS incidents AUTHORIZATION ${POSTGRES_USER};
    CREATE SCHEMA IF NOT EXISTS audit AUTHORIZATION ${POSTGRES_USER};
    CREATE SCHEMA IF NOT EXISTS runbooks AUTHORIZATION ${POSTGRES_USER};

    GRANT USAGE ON SCHEMA incidents, audit, runbooks TO ${POSTGRES_APP_USER};

    ALTER DEFAULT PRIVILEGES IN SCHEMA incidents
        GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${POSTGRES_APP_USER};
    ALTER DEFAULT PRIVILEGES IN SCHEMA audit
        GRANT SELECT, INSERT ON TABLES TO ${POSTGRES_APP_USER};
    ALTER DEFAULT PRIVILEGES IN SCHEMA runbooks
        GRANT SELECT, INSERT, UPDATE ON TABLES TO ${POSTGRES_APP_USER};
EOSQL

echo "SentinelOps: schemas 'incidents', 'audit', 'runbooks' are ready."
