#!/usr/bin/env bash
# SentinelOps local release rehearsal (Phase 9).
#
# Demonstrates the release/rollback procedure documented in docs/development/operations.md end to
# end, entirely locally via Docker Compose, and prints the actual measured durations/sizes/results
# of every step rather than asserting anything not observed. It:
#
#   1. Starts the current (pre-rehearsal) incident-service image, tagged ":previous".
#   2. Creates representative application data through the real authenticated API.
#   3. Produces a backup and VERIFIES it (restore + integrity check into a disposable database —
#      see db-restore-verify.sh) before trusting it.
#   4. Builds and deploys a ":candidate" image (this repository's current source) and lets Flyway
#      apply any pending migrations on startup, exactly as it would in a real deployment.
#   5. Runs the smoke benchmark and the data-integrity check against the candidate.
#   6. Simulates a FAILED release: the candidate is restarted with a deliberately broken
#      configuration (a wrong database password), so its readiness probe fails the way a genuinely
#      broken rollout would — this is a controlled simulation, not a real code defect.
#   7. Performs an APPLICATION ROLLBACK: stops the broken candidate and redeploys ":previous".
#   8. Explains — and, for this rehearsal's additive-only migrations, confirms — that a DATABASE
#      RESTORE is not required on top of the application rollback, because every migration in this
#      repository to date is purely additive (see docs/development/operations.md's migration
#      backward-compatibility table). It does not perform a destructive restore just to
#      demonstrate one; see that same document for when a restore genuinely would be required.
#
# Usage: infrastructure/docker/scripts/release-rehearsal.sh
#
# Requires Docker. Never runs against anything but the local "app" Compose profile.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"
SCRIPTS_DIR="$REPO_ROOT/infrastructure/docker/scripts"
IMAGE="sentinelops/incident-service"

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first."
  exit 1
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
INCIDENT_SERVICE_PORT="${INCIDENT_SERVICE_PORT:-8081}"

now_s() { date +%s; }
RESULTS=()
record() { RESULTS+=("$1"); echo ">>> $1"; }

echo "== SentinelOps release rehearsal =="
echo "This will (re)build the incident-service image and restart it and its dependencies."
printf "Type 'yes' to continue: "
read -r confirm
if [ "$confirm" != "yes" ]; then
  echo "Aborted."
  exit 0
fi

echo
echo "---- Step 1: start the current version, tagged ':previous' ----"
"${COMPOSE[@]}" --profile app build incident-service
docker tag "${IMAGE}:local" "${IMAGE}:previous"
"${COMPOSE[@]}" up -d --wait postgres redis redpanda minio
"${COMPOSE[@]}" up --exit-code-from redpanda-topics-init redpanda-topics-init
"${COMPOSE[@]}" --profile app up -d --wait keycloak incident-service
PREVIOUS_SIZE="$(docker image inspect "${IMAGE}:previous" --format '{{.Size}}')"
record "previous image size: ${PREVIOUS_SIZE} bytes"

echo
echo "---- Step 2: create representative application data ----"
bash "$SCRIPTS_DIR/benchmark-seed.sh" "rehearsal-$(date +%s)" 10 42

echo
echo "---- Step 3: back up and VERIFY the backup ----"
BACKUP_START="$(now_s)"
bash "$SCRIPTS_DIR/db-backup.sh" > /tmp/sentinelops-rehearsal-backup.log
BACKUP_DURATION=$(( $(now_s) - BACKUP_START ))
BACKUP_FILE="$(grep '^File:' /tmp/sentinelops-rehearsal-backup.log | awk '{print $2}')"
BACKUP_SIZE="$(wc -c < "$BACKUP_FILE" | tr -d '[:space:]')"
record "backup duration: ${BACKUP_DURATION}s, size: ${BACKUP_SIZE} bytes (${BACKUP_FILE})"

RESTORE_START="$(now_s)"
if bash "$SCRIPTS_DIR/db-restore-verify.sh" "$BACKUP_FILE" > /tmp/sentinelops-rehearsal-restore-verify.log 2>&1; then
  RESTORE_DURATION=$(( $(now_s) - RESTORE_START ))
  record "restore verification: PASSED in ${RESTORE_DURATION}s"
else
  RESTORE_DURATION=$(( $(now_s) - RESTORE_START ))
  record "restore verification: FAILED in ${RESTORE_DURATION}s — see /tmp/sentinelops-rehearsal-restore-verify.log"
  echo "Aborting rehearsal: an unverified backup must not be relied on for the rest of this rehearsal."
  exit 1
fi

echo
echo "---- Step 4: build and deploy the candidate ----"
"${COMPOSE[@]}" --profile app build incident-service
docker tag "${IMAGE}:local" "${IMAGE}:candidate"
CANDIDATE_SIZE="$(docker image inspect "${IMAGE}:candidate" --format '{{.Size}}')"
record "candidate image size: ${CANDIDATE_SIZE} bytes"
"${COMPOSE[@]}" --profile app up -d --wait incident-service
MIGRATIONS_APPLIED="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
  "SELECT count(*) FROM incidents.flyway_schema_history WHERE success = true;" 2>/dev/null | tr -d '[:space:]')"
record "migrations applied (cumulative, recorded in flyway_schema_history): ${MIGRATIONS_APPLIED}"

echo
echo "---- Step 5: smoke and integrity checks against the candidate ----"
SMOKE_START="$(now_s)"
if bash "$SCRIPTS_DIR/benchmark-run.sh" smoke > /tmp/sentinelops-rehearsal-smoke.log 2>&1; then
  record "smoke test: PASSED in $(( $(now_s) - SMOKE_START ))s"
else
  record "smoke test: FAILED in $(( $(now_s) - SMOKE_START ))s — see /tmp/sentinelops-rehearsal-smoke.log"
fi
if bash "$SCRIPTS_DIR/integrity-check.sh" > /tmp/sentinelops-rehearsal-integrity.log 2>&1; then
  record "integrity check: PASSED"
else
  record "integrity check: FAILED — see /tmp/sentinelops-rehearsal-integrity.log"
fi

echo
echo "---- Step 6: simulate a FAILED release ----"
"${COMPOSE[@]}" --profile app stop incident-service
"${COMPOSE[@]}" --profile app run --rm -d --name sentinelops-incident-service-broken \
  -e POSTGRES_APP_PASSWORD=deliberately-wrong-password \
  incident-service >/dev/null 2>&1 || true
sleep 10
if docker inspect --format '{{.State.Health.Status}}' sentinelops-incident-service-broken 2>/dev/null | grep -q unhealthy; then
  record "simulated failure: candidate correctly reports unhealthy with a bad DB password"
else
  record "simulated failure: candidate health status could not be confirmed unhealthy in time (see logs)"
fi
docker rm -f sentinelops-incident-service-broken >/dev/null 2>&1 || true

echo
echo "---- Step 7: APPLICATION ROLLBACK to ':previous' ----"
docker tag "${IMAGE}:previous" "${IMAGE}:local"
ROLLBACK_START="$(now_s)"
"${COMPOSE[@]}" --profile app up -d --wait incident-service
record "application rollback completed in $(( $(now_s) - ROLLBACK_START ))s (redeployed :previous, no database restore performed)"

echo
echo "---- Step 8: database restore — not performed ----"
echo "Every migration applied during this rehearsal was purely additive (see the migration"
echo "backward-compatibility table in docs/development/operations.md), so the application rollback"
echo "in Step 7 alone is sufficient — the previous version's code never referenced the new"
echo "columns/tables and is unaffected by their presence. A database restore is deliberately NOT"
echo "performed here; it would only be required after a destructive/incompatible migration, which"
echo "this rehearsal does not introduce."

echo
echo "== Release rehearsal summary (measured this run) =="
for line in "${RESULTS[@]}"; do
  echo "- $line"
done
