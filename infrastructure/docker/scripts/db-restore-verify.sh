#!/usr/bin/env bash
# SentinelOps restore-verification workflow (Phase 9).
#
# Proves a backup is actually usable — a completed pg_dump alone is not evidence of that. Creates
# an isolated, disposable database on the running postgres container, restores the given backup
# into it, checks Flyway's own migration history for failures, runs the data-integrity check
# against it, confirms representative incidents/audit/outbox rows are present, and then drops
# ONLY that disposable database — nothing else on the postgres container is touched.
#
# Usage:
#   infrastructure/docker/scripts/db-restore-verify.sh <backupFile>
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"
SCRIPTS_DIR="$REPO_ROOT/infrastructure/docker/scripts"

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first."
  exit 1
fi
if [ $# -lt 1 ]; then
  echo "Usage: $0 <backupFile>"
  exit 1
fi
BACKUP_FILE="$1"
if [ ! -f "$BACKUP_FILE" ]; then
  echo "FAIL: backup file not found: $BACKUP_FILE"
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${POSTGRES_USER:?FAIL: POSTGRES_USER is not set in .env}"
: "${POSTGRES_PASSWORD:?FAIL: POSTGRES_PASSWORD is not set in .env}"

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

if ! "${COMPOSE[@]}" ps postgres 2>/dev/null | grep -q postgres; then
  echo "FAIL: the postgres container is not running. Run 'make infra-up' first."
  exit 1
fi

VERIFY_DB="sentinelops_restore_verify_$(date +%s)_$$"
echo "== Restore verification: ${VERIFY_DB} (from $(basename "$BACKUP_FILE")) =="

cleanup() {
  echo "Dropping temporary verification database '${VERIFY_DB}' (only this database is removed)..."
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
    psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS \"${VERIFY_DB}\";" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Creating temporary database..."
"${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE \"${VERIFY_DB}\";"

echo "Restoring backup into it..."
if ! bash "$SCRIPTS_DIR/db-restore.sh" "$BACKUP_FILE" "$VERIFY_DB" --yes; then
  echo "FAIL: restore into the temporary database failed."
  exit 1
fi

echo
echo "== Checking Flyway migration history for failures =="
FAILED_MIGRATIONS="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d "$VERIFY_DB" -tAc \
  "SELECT count(*) FROM incidents.flyway_schema_history WHERE success = false;" 2>/dev/null | tr -d '[:space:]')"
if [ "$FAILED_MIGRATIONS" != "0" ]; then
  echo "FAIL: ${FAILED_MIGRATIONS:-unknown} migration(s) recorded as failed in the restored database."
  exit 1
fi
echo "OK:   no failed migrations recorded."

APPLIED_MIGRATIONS="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d "$VERIFY_DB" -tAc \
  "SELECT count(*) FROM incidents.flyway_schema_history WHERE success = true;" 2>/dev/null | tr -d '[:space:]')"
echo "Migrations applied (recorded in flyway_schema_history): ${APPLIED_MIGRATIONS:-unknown}"

echo
echo "== Running the data-integrity check against the restored database =="
if ! bash "$SCRIPTS_DIR/integrity-check.sh" "$VERIFY_DB"; then
  echo "FAIL: integrity check failed against the restored database."
  exit 1
fi

echo
echo "== Confirming representative entities are present =="
INCIDENT_COUNT="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d "$VERIFY_DB" -tAc "SELECT count(*) FROM incidents.incidents;" 2>/dev/null | tr -d '[:space:]')"
AUDIT_COUNT="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d "$VERIFY_DB" -tAc "SELECT count(*) FROM audit.audit_events;" 2>/dev/null | tr -d '[:space:]')"
OUTBOX_COUNT="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d "$VERIFY_DB" -tAc "SELECT count(*) FROM incidents.outbox_events;" 2>/dev/null | tr -d '[:space:]')"

echo "incidents:     ${INCIDENT_COUNT:-unknown}"
echo "audit_events:  ${AUDIT_COUNT:-unknown}"
echo "outbox_events: ${OUTBOX_COUNT:-unknown}"

if [ "${INCIDENT_COUNT:-0}" = "0" ]; then
  echo "WARNING: the restored database has zero incidents — this backup may have been taken"
  echo "         against an empty database, or the restore did not bring data across. A backup"
  echo "         of an intentionally empty database is not itself a failure, but confirm this is"
  echo "         what you expected before trusting this backup for a populated environment."
fi

echo
echo "== Restore verification PASSED for $(basename "$BACKUP_FILE") =="
