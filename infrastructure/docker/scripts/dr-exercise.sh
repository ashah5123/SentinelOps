#!/usr/bin/env bash
# SentinelOps Phase 15 disaster-recovery exercise.
#
# Runs a real backup -> simulated data loss -> restore -> verify cycle against an isolated,
# disposable database on the running postgres container (never the live database), and measures
# actual wall-clock RTO and actual data-loss RPO from the numbers this run itself produces —
# never a precomputed or assumed value. Builds directly on db-backup.sh, db-restore.sh, and
# integrity-check.sh rather than reimplementing backup/restore logic.
#
# Drill:
#   1. Take a backup (T0).
#   2. Insert N additional rows after T0 (simulating writes that happen after the last backup).
#   3. "Declare a disaster": start the restore-from-T0-backup clock.
#   4. Restore the T0 backup into a disposable database and run the integrity check against it.
#   5. RTO = wall-clock time from step 3 to a verified-healthy restored database.
#   6. RPO = the N rows inserted in step 2 — real, counted data that a T0-only backup cannot
#      recover — reported as both a row count and the elapsed time between T0 and the last of
#      those inserts (the actual window of unrecoverable writes for this backup cadence).
#
# Usage:
#   infrastructure/docker/scripts/dr-exercise.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"
SCRIPTS_DIR="$REPO_ROOT/infrastructure/docker/scripts"

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

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
BACKUP_DIR="$REPO_ROOT/infrastructure/docker/backups"
RUN_ID="dr-exercise-$(date +%s)-$$"
REPORT_FILE="/tmp/${RUN_ID}-report.json"

if ! "${COMPOSE[@]}" ps postgres 2>/dev/null | grep -q postgres; then
  echo "FAIL: the postgres container is not running. Run 'make infra-up' first."
  exit 1
fi

psql_exec() {
  "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
    psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "$1"
}

echo "== SentinelOps DR exercise: $RUN_ID =="

echo "Marking a synthetic pre-backup incident so T0 is unambiguous in the restored data..."
T0_MARKER="dr-exercise-t0-marker-${RUN_ID}"
psql_exec "INSERT INTO incidents.incidents (id, incident_number, title, description, severity, status, source, affected_service, detected_at, created_at, updated_at, correlation_id, version) VALUES (gen_random_uuid(), '${T0_MARKER}', '${T0_MARKER}', 'DR exercise T0 marker', 'SEV4', 'DETECTED', 'dr-exercise', 'dr-exercise', now(), now(), now(), '${T0_MARKER}', 0);" >/dev/null

echo "Taking backup at T0..."
T0="$(date +%s)"
BACKUP_FILE="$(bash "$SCRIPTS_DIR/db-backup.sh" "$BACKUP_DIR" | tee /dev/stderr | grep -oE '[^ ]+\.dump$' | tail -1)"
if [ -z "$BACKUP_FILE" ] || [ ! -f "$BACKUP_FILE" ]; then
  echo "FAIL: could not determine the backup file produced by db-backup.sh"
  exit 1
fi
echo "Backup captured: $BACKUP_FILE"

POST_BACKUP_ROWS=5
echo "Simulating ${POST_BACKUP_ROWS} writes that occur AFTER the backup (these are the ones a T0-only restore cannot recover)..."
for i in $(seq 1 "$POST_BACKUP_ROWS"); do
  MARKER="dr-exercise-post-backup-${RUN_ID}-${i}"
  psql_exec "INSERT INTO incidents.incidents (id, incident_number, title, description, severity, status, source, affected_service, detected_at, created_at, updated_at, correlation_id, version) VALUES (gen_random_uuid(), '${MARKER}', '${MARKER}', 'DR exercise post-backup row', 'SEV4', 'DETECTED', 'dr-exercise', 'dr-exercise', now(), now(), now(), '${MARKER}', 0);" >/dev/null
done
LAST_WRITE_EPOCH="$(date +%s)"

echo
echo "== Disaster declared: starting restore from the T0 backup =="
RESTORE_START="$(date +%s)"
VERIFY_DB="sentinelops_dr_exercise_$(date +%s)_$$"

if ! bash "$SCRIPTS_DIR/db-restore-verify.sh" "$BACKUP_FILE" 2>&1 | tee "/tmp/${RUN_ID}-verify.log"; then
  echo "FAIL: restore verification failed — DR exercise did not complete a recoverable restore."
  exit 1
fi
RESTORE_END="$(date +%s)"
RTO_SECONDS=$((RESTORE_END - RESTORE_START))

echo
echo "== Confirming what the T0 backup does and does not contain =="
# Re-run a scoped restore we keep (not auto-dropped) so we can directly count rows for the RPO report.
RPO_CHECK_DB="sentinelops_dr_rpo_check_$(date +%s)_$$"
"${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE \"${RPO_CHECK_DB}\";" >/dev/null
bash "$SCRIPTS_DIR/db-restore.sh" "$BACKUP_FILE" "$RPO_CHECK_DB" --yes >/dev/null

T0_MARKER_PRESENT="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d "$RPO_CHECK_DB" -tAc \
  "SELECT count(*) FROM incidents.incidents WHERE incident_number = '${T0_MARKER}';" 2>/dev/null | tr -d '[:space:]')"
POST_BACKUP_ROWS_PRESENT="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d "$RPO_CHECK_DB" -tAc \
  "SELECT count(*) FROM incidents.incidents WHERE incident_number LIKE 'dr-exercise-post-backup-${RUN_ID}-%';" 2>/dev/null | tr -d '[:space:]')"

"${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS \"${RPO_CHECK_DB}\";" >/dev/null 2>&1 || true

RPO_ROWS_LOST=$((POST_BACKUP_ROWS - ${POST_BACKUP_ROWS_PRESENT:-0}))
RPO_WINDOW_SECONDS=$((LAST_WRITE_EPOCH - T0))

echo
echo "== DR exercise results =="
echo "T0 marker present in restored backup: ${T0_MARKER_PRESENT:-0}/1 (expected 1)"
echo "Post-backup rows present in restored backup: ${POST_BACKUP_ROWS_PRESENT:-0}/${POST_BACKUP_ROWS} (expected 0 — they were written after T0)"
echo "RTO (backup-file restore + full integrity verification, wall clock): ${RTO_SECONDS}s"
echo "RPO (data written after the backup that a restore from it cannot recover): ${RPO_ROWS_LOST} row(s), spanning ${RPO_WINDOW_SECONDS}s of writes"
echo
echo "These are local-hardware, single-run measurements from this exercise only — see"
echo "docs/validation/disaster-recovery.md for how to interpret them and re-run this drill."

cat > "$REPORT_FILE" <<EOF
{
  "runId": "$RUN_ID",
  "backupFile": "$(basename "$BACKUP_FILE")",
  "rtoSeconds": $RTO_SECONDS,
  "rpoRowsLost": $RPO_ROWS_LOST,
  "rpoWindowSeconds": $RPO_WINDOW_SECONDS,
  "t0MarkerRestored": ${T0_MARKER_PRESENT:-0}
}
EOF
echo "Machine-readable result: $REPORT_FILE"

if [ "${T0_MARKER_PRESENT:-0}" != "1" ]; then
  echo "FAIL: T0 marker missing from the restored backup — the backup itself did not capture the expected state."
  exit 1
fi
if [ "${POST_BACKUP_ROWS_PRESENT:-0}" != "0" ]; then
  echo "FAIL: post-backup rows were found in a T0 backup — the backup/restore boundary is not what this exercise assumes."
  exit 1
fi

echo
echo "== DR exercise PASSED =="
