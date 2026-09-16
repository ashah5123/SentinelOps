#!/usr/bin/env bash
# SentinelOps data-integrity check (Phase 9).
#
# Runs a bounded set of read-only SQL checks against a running PostgreSQL database and reports
# any violation found, without ever printing row contents (only counts and identifiers already
# safe to show — incident IDs and action names, never audit metadata payloads or credentials).
# Exits non-zero if any check finds a violation.
#
# Usage:
#   infrastructure/docker/scripts/integrity-check.sh [databaseName]
#
# Default databaseName: $POSTGRES_DB (the normal development database). Pass a different name to
# check a restored/temporary database instead (see db-restore-verify.sh).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first."
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${POSTGRES_USER:?FAIL: POSTGRES_USER is not set in .env}"
: "${POSTGRES_PASSWORD:?FAIL: POSTGRES_PASSWORD is not set in .env}"
: "${POSTGRES_DB:?FAIL: POSTGRES_DB is not set in .env}"

DATABASE="${1:-$POSTGRES_DB}"
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

if ! "${COMPOSE[@]}" ps postgres 2>/dev/null | grep -q postgres; then
  echo "FAIL: the postgres container is not running. Run 'make infra-up' first."
  exit 1
fi

VIOLATIONS=0

# run_check <description> <sql returning a single count of violating rows>
run_check() {
  local description="$1"
  local sql="$2"
  local count
  count="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
    psql -U "$POSTGRES_USER" -d "$DATABASE" -tAc "$sql" 2>/dev/null | tr -d '[:space:]')"
  if [ -z "$count" ]; then
    echo "ERROR: could not evaluate check: ${description}"
    VIOLATIONS=$((VIOLATIONS + 1))
    return
  fi
  if [ "$count" = "0" ]; then
    echo "OK:   ${description}"
  else
    echo "FAIL: ${description} — ${count} violating row(s)"
    VIOLATIONS=$((VIOLATIONS + 1))
  fi
}

echo "== SentinelOps data-integrity check: database '${DATABASE}' =="

run_check \
  "every incident has a lifecycle status defined by the application (DETECTED, INVESTIGATING, AWAITING_APPROVAL, MITIGATING, RESOLVED, FAILED)" \
  "SELECT count(*) FROM incidents.incidents WHERE status NOT IN ('DETECTED','INVESTIGATING','AWAITING_APPROVAL','MITIGATING','RESOLVED','FAILED')"

run_check \
  "every incident's severity is one this application defines (SEV1-SEV4)" \
  "SELECT count(*) FROM incidents.incidents WHERE severity NOT IN ('SEV1','SEV2','SEV3','SEV4')"

run_check \
  "no incident has updated_at before created_at" \
  "SELECT count(*) FROM incidents.incidents WHERE updated_at < created_at"

run_check \
  "no RESOLVED incident is missing resolved_at, and no non-RESOLVED incident has one set" \
  "SELECT count(*) FROM incidents.incidents WHERE (status = 'RESOLVED' AND resolved_at IS NULL) OR (status <> 'RESOLVED' AND resolved_at IS NOT NULL)"

run_check \
  "no incident_status_history row is timestamped before its incident's detected_at" \
  "SELECT count(*) FROM incidents.incident_status_history h JOIN incidents.incidents i ON i.id = h.incident_id WHERE h.occurred_at < i.detected_at"

run_check \
  "every audit_events row has a non-blank action, actor_type, actor_id, and correlation_id" \
  "SELECT count(*) FROM audit.audit_events WHERE action IS NULL OR btrim(action) = '' OR actor_id IS NULL OR btrim(actor_id) = '' OR correlation_id IS NULL OR btrim(correlation_id) = ''"

run_check \
  "every audit_events row referencing an incident points at one that actually exists" \
  "SELECT count(*) FROM audit.audit_events a WHERE a.incident_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM incidents.incidents i WHERE i.id = a.incident_id)"

run_check \
  "every outbox_events row has a non-empty JSON object payload" \
  "SELECT count(*) FROM incidents.outbox_events WHERE payload IS NULL OR jsonb_typeof(payload) <> 'object'"

run_check \
  "every outbox_events row has a recognized status (PENDING, PUBLISHED, FAILED)" \
  "SELECT count(*) FROM incidents.outbox_events WHERE status NOT IN ('PENDING','PUBLISHED','FAILED')"

run_check \
  "no PUBLISHED outbox_events row is missing published_at, and no non-PUBLISHED row has one set" \
  "SELECT count(*) FROM incidents.outbox_events WHERE (status = 'PUBLISHED' AND published_at IS NULL) OR (status <> 'PUBLISHED' AND published_at IS NOT NULL)"

run_check \
  "no duplicate idempotency keys exist (idempotency_key is the primary key, so this only catches a corrupted index)" \
  "SELECT count(*) FROM (SELECT idempotency_key FROM incidents.idempotent_requests GROUP BY idempotency_key HAVING count(*) > 1) d"

run_check \
  "no two incidents share the same source_event_id (anomaly-consumption idempotency)" \
  "SELECT count(*) FROM (SELECT source_event_id FROM incidents.incidents WHERE source_event_id IS NOT NULL GROUP BY source_event_id HAVING count(*) > 1) d"

echo
if [ "$VIOLATIONS" -eq 0 ]; then
  echo "== Integrity check passed: 0 violations =="
  exit 0
else
  echo "== Integrity check FAILED: ${VIOLATIONS} check(s) found violations =="
  exit 1
fi
