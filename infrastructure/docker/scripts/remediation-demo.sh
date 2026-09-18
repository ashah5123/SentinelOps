#!/usr/bin/env bash
# SentinelOps Phase 14 local end-to-end remediation demonstration.
#
# Drives the policy-controlled remediation engine directly with curl against a running
# incident-service — deterministic and fully reproducible. Requires the "app" Compose profile
# already running: `make incident-up` or `docker compose --profile app up -d`.
#
# Usage:
#   infrastructure/docker/scripts/remediation-demo.sh [incidentServiceUrl]
#
# Exercises, in order:
#   1. Propose the LOW-risk "clear-triage-search-cache" runbook as a RESPONDER — policy ALLOWs it
#      (unrestricted environment), so it is auto-approved and scheduled without human review.
#   2. Poll until the scheduler picks it up and it reaches a terminal state (SUCCEEDED).
#   3. Propose the MEDIUM-risk "restart-checkout-service" runbook — policy REQUIRE_APPROVALs it.
#   4. Approve it as a second, distinct RESPONDER (self-approval is rejected server-side).
#   5. Poll until it succeeds.
#   6. Force the target deployment unhealthy via the diagnostic fault-injection endpoint, then
#      propose the same runbook again — the scheduler's post-execution health check fails and it
#      automatically rolls back, landing on ROLLED_BACK with a populated rollback_reason.
#   7. Fetch the step-level detail and audit trail for the rolled-back execution.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"
BASE_URL="${1:-http://localhost:8081}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

# shellcheck disable=SC1091
source "$REPO_ROOT/infrastructure/docker/scripts/lib/auth.sh"

pass=0
fail=0

check() {
  local description="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    echo "PASS: $description (HTTP $actual)"
    pass=$((pass + 1))
  else
    echo "FAIL: $description (expected HTTP $expected, got $actual)"
    fail=$((fail + 1))
  fi
}

propose() {
  local token="$1" slug="$2" idempotency_key="$3"
  curl -s -o /tmp/remediation-demo-response.json -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/remediations" \
    -H "Authorization: Bearer $token" \
    -H "Content-Type: application/json" \
    -d "{\"runbookSlug\":\"$slug\",\"dryRun\":false,\"idempotencyKey\":\"$idempotency_key\"}"
}

get_execution() {
  local token="$1" id="$2"
  curl -fsS "$BASE_URL/api/v1/remediations/$id" -H "Authorization: Bearer $token"
}

field() {
  python3 -c 'import sys, json; print(json.load(sys.stdin).get(sys.argv[1], ""))' "$1"
}

wait_for_terminal_status() {
  local token="$1" id="$2" attempt=0 status=""
  while [ "$attempt" -lt 30 ]; do
    status=$(get_execution "$token" "$id" | field status)
    case "$status" in
      SUCCEEDED | FAILED | ROLLED_BACK | CANCELLED | DENIED)
        echo "$status"
        return 0
        ;;
    esac
    attempt=$((attempt + 1))
    sleep 1
  done
  echo "$status"
}

echo "== SentinelOps Phase 14 remediation demonstration against $BASE_URL =="

RESPONDER_TOKEN="$(fetch_incident_service_token responder-demo)"
RESPONDER2_TOKEN="$(fetch_incident_service_token admin-demo)"
RUN_ID="demo-$(date +%s)-$$"

# 1. LOW-risk runbook — auto-approved (unrestricted environment).
code=$(propose "$RESPONDER_TOKEN" "clear-triage-search-cache" "$RUN_ID-low")
check "propose low-risk runbook accepted" "201" "$code"
LOW_ID=$(field id </tmp/remediation-demo-response.json)
LOW_STATUS=$(field status </tmp/remediation-demo-response.json)
echo "  execution $LOW_ID initial status: $LOW_STATUS (expect SCHEDULED — auto-approved)"

# 2. Poll for completion.
FINAL_LOW_STATUS=$(wait_for_terminal_status "$RESPONDER_TOKEN" "$LOW_ID")
check "low-risk runbook reaches SUCCEEDED" "SUCCEEDED" "$FINAL_LOW_STATUS"

# 3. MEDIUM-risk runbook — requires approval.
code=$(propose "$RESPONDER_TOKEN" "restart-checkout-service" "$RUN_ID-medium")
check "propose medium-risk runbook accepted" "201" "$code"
MEDIUM_ID=$(field id </tmp/remediation-demo-response.json)
MEDIUM_STATUS=$(field status </tmp/remediation-demo-response.json)
echo "  execution $MEDIUM_ID initial status: $MEDIUM_STATUS (expect PROPOSED — awaiting approval)"

# 4. Approve as a distinct actor (self-approval would be rejected).
approve_code=$(curl -s -o /tmp/remediation-demo-response.json -w '%{http_code}' \
  -X POST "$BASE_URL/api/v1/remediations/$MEDIUM_ID/approve" \
  -H "Authorization: Bearer $RESPONDER2_TOKEN" -H "Content-Type: application/json" -d '{}')
check "distinct-actor approval accepted" "200" "$approve_code"

# 5. Poll for completion.
FINAL_MEDIUM_STATUS=$(wait_for_terminal_status "$RESPONDER_TOKEN" "$MEDIUM_ID")
check "medium-risk runbook reaches SUCCEEDED after approval" "SUCCEEDED" "$FINAL_MEDIUM_STATUS"

# 6. Fault injection: force the target deployment unhealthy, then propose the same runbook again.
#    See docs/development/remediation.md's "local implementations" section — this is the
#    documented, deterministic hook for demonstrating automatic rollback without a real cluster.
echo "  forcing checkout-api/staging unhealthy via direct SQL fault injection (demo-only)..."
docker compose --env-file "$ENV_FILE" -f "$REPO_ROOT/infrastructure/docker/docker-compose.yml" \
  exec -T postgres psql -U "${POSTGRES_USER:-sentinelops}" -d "${POSTGRES_DB:-sentinelops}" \
  -c "UPDATE incidents.simulated_deployments SET healthy = false WHERE service = 'checkout-api' AND environment = 'staging';" \
  >/dev/null 2>&1 || echo "  (fault injection requires the app Compose profile; skipping if unavailable)"

code=$(propose "$RESPONDER_TOKEN" "restart-checkout-service" "$RUN_ID-rollback")
check "propose against unhealthy target accepted" "201" "$code"
ROLLBACK_ID=$(field id </tmp/remediation-demo-response.json)

approve_code=$(curl -s -o /dev/null -w '%{http_code}' \
  -X POST "$BASE_URL/api/v1/remediations/$ROLLBACK_ID/approve" \
  -H "Authorization: Bearer $RESPONDER2_TOKEN" -H "Content-Type: application/json" -d '{}')
check "approval for the will-fail-health-check execution accepted" "200" "$approve_code"

FINAL_ROLLBACK_STATUS=$(wait_for_terminal_status "$RESPONDER_TOKEN" "$ROLLBACK_ID")
check "unhealthy-target execution automatically rolls back" "ROLLED_BACK" "$FINAL_ROLLBACK_STATUS"

echo
echo "  step detail for the rolled-back execution:"
curl -fsS "$BASE_URL/api/v1/remediations/$ROLLBACK_ID/steps" -H "Authorization: Bearer $RESPONDER_TOKEN"
echo

echo
echo "== Results: $pass passed, $fail failed =="
[ "$fail" -eq 0 ]
