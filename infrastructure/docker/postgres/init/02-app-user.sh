#!/usr/bin/env bash
# Creates a non-superuser application role for SentinelOps services.
# Runs once, automatically, on first container initialization (standard
# Postgres docker-entrypoint-initdb.d semantics: only executed against an
# empty data directory). The SQL itself is written to be idempotent so it
# is also safe to re-run manually against an existing database.
set -euo pipefail

: "${POSTGRES_APP_USER:?POSTGRES_APP_USER must be set}"
: "${POSTGRES_APP_PASSWORD:?POSTGRES_APP_PASSWORD must be set}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    DO \$\$
    BEGIN
        IF NOT EXISTS (
            SELECT FROM pg_catalog.pg_roles WHERE rolname = '${POSTGRES_APP_USER}'
        ) THEN
            CREATE ROLE ${POSTGRES_APP_USER}
                LOGIN
                PASSWORD '${POSTGRES_APP_PASSWORD}'
                NOSUPERUSER
                NOCREATEDB
                NOCREATEROLE;
        END IF;
    END
    \$\$;

    GRANT CONNECT ON DATABASE ${POSTGRES_DB} TO ${POSTGRES_APP_USER};
EOSQL

echo "SentinelOps: application role '${POSTGRES_APP_USER}' is ready (non-superuser)."
