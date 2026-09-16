#!/usr/bin/env bash
# SentinelOps Phase 8 benchmark data cleanup.
#
# Deletes ONLY rows explicitly identified as benchmark data: incidents whose affected_service
# equals "bench-<runId>" (as created by benchmark-seed.sh and every k6 scenario in
# infrastructure/docker/k6/), their status-history/evidence rows, their audit_events and
# outbox_events rows, and any idempotent_requests rows keyed with a benchmark-script prefix.
# Never touches a row it did not explicitly match by these markers — real development data using
# any other affected_service value is untouched, and this script never runs a bare
# "DELETE FROM incidents" or similar unscoped statement.
#
# Usage:
#   infrastructure/docker/scripts/benchmark-cleanup.sh <runId>     # delete one run's data
#   infrastructure/docker/scripts/benchmark-cleanup.sh --all-bench # delete every "bench-*" run
#                                                                   # (interactive confirmation)
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

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

if [ $# -lt 1 ]; then
  echo "Usage: $0 <runId>|--all-bench"
  exit 1
fi

if [ "$1" = "--all-bench" ]; then
  AFFECTED_SERVICE_MATCH="affected_service LIKE 'bench-%'"
  DESCRIPTION="every benchmark run (affected_service LIKE 'bench-%')"
else
  RUN_ID="$1"
  AFFECTED_SERVICE_MATCH="affected_service = 'bench-${RUN_ID}'"
  DESCRIPTION="run '${RUN_ID}' only (affected_service = 'bench-${RUN_ID}')"
fi

run_sql() {
  "${COMPOSE[@]}" exec -T postgres psql -U "$POSTGRES_APP_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1 -c "$1"
}

echo "== Benchmark data cleanup: ${DESCRIPTION} =="

COUNT="$("${COMPOSE[@]}" exec -T postgres psql -U "$POSTGRES_APP_USER" -d "$POSTGRES_DB" -tAc \
  "SELECT count(*) FROM incidents.incidents WHERE ${AFFECTED_SERVICE_MATCH};" 2>/dev/null | tr -d '[:space:]')"

if [ -z "$COUNT" ] || [ "$COUNT" = "0" ]; then
  echo "No matching benchmark incidents found. Nothing to do."
  exit 0
fi

echo "This will permanently delete ${COUNT} incident(s) matching ${DESCRIPTION}, plus their"
echo "status-history, evidence, audit, and outbox rows. Real development data is never touched"
echo "(no other affected_service value is matched)."
printf "Type 'yes' to continue: "
read -r confirm
if [ "$confirm" != "yes" ]; then
  echo "Aborted. Nothing was changed."
  exit 0
fi

run_sql "
  DELETE FROM incidents.incident_status_history
  WHERE incident_id IN (SELECT id FROM incidents.incidents WHERE ${AFFECTED_SERVICE_MATCH});
"
run_sql "
  DELETE FROM incidents.incident_evidence
  WHERE incident_id IN (SELECT id FROM incidents.incidents WHERE ${AFFECTED_SERVICE_MATCH});
"
run_sql "
  DELETE FROM audit.audit_events
  WHERE incident_id IN (SELECT id FROM incidents.incidents WHERE ${AFFECTED_SERVICE_MATCH});
"
run_sql "
  DELETE FROM incidents.outbox_events
  WHERE aggregate_id IN (SELECT id FROM incidents.incidents WHERE ${AFFECTED_SERVICE_MATCH});
"
run_sql "
  DELETE FROM incidents.idempotent_requests
  WHERE idempotency_key LIKE 'bench-seed-%'
     OR idempotency_key LIKE 'create-%'
     OR idempotency_key LIKE 'lifecycle-%'
     OR idempotency_key LIKE 'mixed-%'
     OR idempotency_key LIKE 'burst-%'
     OR idempotency_key LIKE 'smoke-%'
     OR idempotency_key LIKE 'unauth-%';
"
run_sql "DELETE FROM incidents.incidents WHERE ${AFFECTED_SERVICE_MATCH};"

echo "== Cleanup complete: ${COUNT} benchmark incident(s) and their related rows removed =="
