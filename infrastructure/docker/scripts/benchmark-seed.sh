#!/usr/bin/env bash
# SentinelOps Phase 8 benchmark data seeding.
#
# Creates a deterministic set of synthetic incidents via the real, authenticated
# POST /api/v1/incidents API (as RESPONDER) — never by writing to the database directly, so the
# seeded data goes through the exact same validation, idempotency, audit, and outbox path as real
# traffic. Every seeded incident shares one affectedService value unique to this run
# ("bench-<runId>"), which is how benchmark-cleanup.sh scopes its deletion to only these records
# and how the k6 read scenarios find them again.
#
# Usage:
#   infrastructure/docker/scripts/benchmark-seed.sh [runId] [datasetSize] [seed]
#
# Defaults: runId is generated, datasetSize=200 (safe for a laptop; override for a larger local
# run), seed=42 (fixed, so the generated titles/severities are reproducible across runs).
#
# Requires the "app" Compose profile already running (make incident-up).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first."
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

INCIDENT_SERVICE_PORT="${INCIDENT_SERVICE_PORT:-8081}"
RUN_ID="${1:-$(date +%s)}"
DATASET_SIZE="${2:-200}"
SEED="${3:-42}"
AFFECTED_SERVICE="bench-${RUN_ID}"

RESULTS_DIR="$REPO_ROOT/infrastructure/docker/k6/results/${RUN_ID}"
mkdir -p "$RESULTS_DIR"
ID_FILE="$RESULTS_DIR/seeded-incident-ids.txt"
: > "$ID_FILE"

# shellcheck disable=SC1091
source "$REPO_ROOT/infrastructure/docker/scripts/lib/auth.sh"
TOKEN="$(fetch_incident_service_token responder-demo)" || {
  echo "FAIL: could not obtain a responder-demo token from Keycloak. Is 'make incident-up' running?"
  exit 1
}

echo "== Seeding ${DATASET_SIZE} benchmark incidents (runId=${RUN_ID}, seed=${SEED}) =="
echo "affectedService=${AFFECTED_SERVICE} — this is the only marker benchmark-cleanup.sh trusts."

# Deterministic (seeded) severity/title-suffix sequence, generated once so both this script and
# anyone auditing the run can see exactly what was requested.
mapfile -t SEVERITIES < <(python3 -c "
import random
random.seed(${SEED})
for _ in range(${DATASET_SIZE}):
    print(random.choice(['SEV1', 'SEV2', 'SEV3', 'SEV4']))
")

CREATED=0
FAILED=0
for i in $(seq 0 $((DATASET_SIZE - 1))); do
  severity="${SEVERITIES[$i]}"
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  response="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
    "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/api/v1/incidents" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: bench-seed-${RUN_ID}-${i}" \
    -d "{\"title\":\"[benchmark-seed] ${RUN_ID} incident ${i}\",\"description\":\"Deterministic seed data for Phase 8 benchmarking (seed=${SEED}).\",\"severity\":\"${severity}\",\"source\":\"benchmark-seed\",\"affectedService\":\"${AFFECTED_SERVICE}\",\"detectedAt\":\"${now}\"}" \
    -D "$RESULTS_DIR/.last-headers.tmp" 2>/dev/null)"

  if [ "$response" = "201" ]; then
    incident_id="$(grep -i '^location:' "$RESULTS_DIR/.last-headers.tmp" | sed 's#.*/##' | tr -d '\r\n')"
    [ -n "$incident_id" ] && echo "$incident_id" >> "$ID_FILE"
    CREATED=$((CREATED + 1))
  else
    FAILED=$((FAILED + 1))
  fi

  if [ $(((i + 1) % 50)) -eq 0 ]; then
    echo "  ...${CREATED} created, ${FAILED} failed so far"
  fi
done
rm -f "$RESULTS_DIR/.last-headers.tmp"

echo "== Seeding complete: ${CREATED} created, ${FAILED} failed =="
echo "Run ID:            ${RUN_ID}"
echo "affectedService:   ${AFFECTED_SERVICE}"
echo "Incident IDs saved: ${ID_FILE}"
echo
echo "Pass this to k6 scenarios: -e BENCHMARK_RUN_ID=${RUN_ID}"
if [ "$FAILED" -gt 0 ]; then
  echo "WARNING: ${FAILED} seed requests failed — inspect incident-service logs before benchmarking."
  exit 1
fi
