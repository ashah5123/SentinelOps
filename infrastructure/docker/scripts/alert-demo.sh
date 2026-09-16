#!/usr/bin/env bash
# SentinelOps Phase 12 local end-to-end alert demonstration.
#
# Drives incident-service's two alert connectors directly with curl — deterministic and fully
# reproducible, unlike waiting for a real Prometheus rule to actually breach its threshold on
# whatever load happens to be on the box. Requires the "app" (and, for the webhook-sink/mailpit
# checks, no extra profile — both are part of "app" as of Phase 12) Compose profile already
# running: `make incident-up` or `docker compose --profile app up -d`.
#
# Usage:
#   infrastructure/docker/scripts/alert-demo.sh [incidentServiceUrl]
#
# Exercises, in order:
#   1. A firing Alertmanager-format alert (creates an incident).
#   2. A duplicate delivery of the exact same alert (delivery idempotency — no new alert_events row).
#   3. A repeated firing of the same underlying condition with a later startsAt (semantic dedup —
#      occurrence_count increments, no new incident).
#   4. A related alert sharing service+environment (deterministic correlation).
#   5. A resolved alert for the first fingerprint (timeline update, incident stays open).
#   6. A generic-webhook alert signed with HMAC-SHA256.
#   7. A generic-webhook request with a deliberately wrong signature (expect 401).
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

ALERTMANAGER_TOKEN="${ALERTS_ALERTMANAGER_SHARED_TOKEN:-change-me-local-dev-only}"
HMAC_SECRET="${ALERTS_WEBHOOK_HMAC_CURRENT_SECRET:-change-me-local-dev-only}"
HMAC_KEY_ID="${ALERTS_WEBHOOK_HMAC_CURRENT_SECRET_ID:-current}"

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

send_alertmanager_payload() {
  local body="$1"
  curl -s -o /tmp/alert-demo-response.json -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/alerts/webhooks/alertmanager" \
    -H "Authorization: Bearer $ALERTMANAGER_TOKEN" \
    -H "Content-Type: application/json" \
    -d "$body"
}

send_generic_webhook() {
  local body="$1" signature="$2" timestamp="$3" key_id="$4"
  curl -s -o /tmp/alert-demo-response.json -w '%{http_code}' \
    -X POST "$BASE_URL/api/v1/alerts/webhooks/generic/v1" \
    -H "X-SentinelOps-Timestamp: $timestamp" \
    -H "X-SentinelOps-Signature: $signature" \
    -H "X-SentinelOps-Key-Id: $key_id" \
    -H "Content-Type: application/json" \
    -d "$body"
}

hmac_sign() {
  local secret="$1" message="$2"
  printf '%s' "$message" | openssl dgst -sha256 -hmac "$secret" | sed 's/^.* //'
}

echo "== SentinelOps Phase 12 alert demonstration against $BASE_URL =="

# 1. Firing Alertmanager alert.
STARTS_AT_1="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
AM_PAYLOAD_1=$(cat <<EOF
{"version":"4","status":"firing","receiver":"sentinelops-incident-service",
 "alerts":[{"status":"firing",
   "labels":{"alertname":"HighCpuUsage","severity":"critical","service":"checkout-api","environment":"production","instance":"host-demo-1"},
   "annotations":{"summary":"CPU usage above 90%","description":"checkout-api CPU usage has exceeded 90% for 5 minutes"},
   "startsAt":"$STARTS_AT_1","endsAt":"0001-01-01T00:00:00Z",
   "generatorURL":"http://prometheus:9090/graph","fingerprint":"demo-fp-1"}]}
EOF
)
code=$(send_alertmanager_payload "$AM_PAYLOAD_1")
check "firing alert accepted" "202" "$code"
cat /tmp/alert-demo-response.json; echo

# 2. Exact duplicate delivery (same fingerprint/startsAt) — expect the same accepted response,
#    still 202, but internally recorded as a duplicate delivery (see connector metrics/logs).
code=$(send_alertmanager_payload "$AM_PAYLOAD_1")
check "duplicate delivery accepted idempotently" "202" "$code"

# 3. A later occurrence of the same underlying condition (new startsAt, same identity labels) —
#    semantic dedup: increments occurrence_count on the existing incident, no new incident.
sleep 1
STARTS_AT_2="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
AM_PAYLOAD_2=$(echo "$AM_PAYLOAD_1" | sed "s/$STARTS_AT_1/$STARTS_AT_2/" | sed 's/demo-fp-1/demo-fp-1-repeat/')
code=$(send_alertmanager_payload "$AM_PAYLOAD_2")
check "repeated occurrence accepted (semantic dedup applies downstream)" "202" "$code"

# 4. A related alert: same service+environment, different alert name — deterministic correlation
#    should attach it to the same incident rather than creating a second one.
AM_PAYLOAD_3=$(cat <<EOF
{"version":"4","status":"firing","receiver":"sentinelops-incident-service",
 "alerts":[{"status":"firing",
   "labels":{"alertname":"HighMemoryUsage","severity":"warning","service":"checkout-api","environment":"production","instance":"host-demo-2"},
   "annotations":{"summary":"Memory usage above 85%","description":"checkout-api memory usage is elevated"},
   "startsAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)","endsAt":"0001-01-01T00:00:00Z",
   "generatorURL":"http://prometheus:9090/graph","fingerprint":"demo-fp-2"}]}
EOF
)
code=$(send_alertmanager_payload "$AM_PAYLOAD_3")
check "related alert accepted (correlation applies downstream)" "202" "$code"

# 5. Resolve the first alert.
AM_PAYLOAD_RESOLVED=$(cat <<EOF
{"version":"4","status":"resolved","receiver":"sentinelops-incident-service",
 "alerts":[{"status":"resolved",
   "labels":{"alertname":"HighCpuUsage","severity":"critical","service":"checkout-api","environment":"production","instance":"host-demo-1"},
   "annotations":{"summary":"CPU usage above 90%","description":"checkout-api CPU usage has exceeded 90% for 5 minutes"},
   "startsAt":"$STARTS_AT_1","endsAt":"$(date -u +%Y-%m-%dT%H:%M:%SZ)",
   "generatorURL":"http://prometheus:9090/graph","fingerprint":"demo-fp-1"}]}
EOF
)
code=$(send_alertmanager_payload "$AM_PAYLOAD_RESOLVED")
check "resolved alert accepted" "202" "$code"

# 6. Generic webhook, correctly HMAC-signed.
GENERIC_BODY='{"source":"custom-monitor","externalId":"custom-1","status":"firing","alertName":"DiskSpaceLow","summary":"Disk space low","description":"Disk usage above 90%","severity":"SEV3","service":"orders-api","environment":"production","labels":{"instance":"host-demo-3"},"annotations":{},"sourceTimestamp":"'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'","schemaVersion":1}'
TIMESTAMP=$(date +%s)
SIGNATURE=$(hmac_sign "$HMAC_SECRET" "$TIMESTAMP.$GENERIC_BODY")
code=$(send_generic_webhook "$GENERIC_BODY" "$SIGNATURE" "$TIMESTAMP" "$HMAC_KEY_ID")
check "correctly signed generic webhook accepted" "202" "$code"

# 7. Generic webhook with a deliberately wrong signature.
code=$(send_generic_webhook "$GENERIC_BODY" "0000000000000000000000000000000000000000000000000000000000000000" "$TIMESTAMP" "$HMAC_KEY_ID")
check "incorrectly signed generic webhook rejected" "401" "$code"

echo
echo "== Results: $pass passed, $fail failed =="
echo "Inspect http://localhost:\${MAILPIT_UI_PORT:-8025} for the email notification and"
echo "curl http://localhost:\${WEBHOOK_SINK_PORT:-9099}/received for the webhook notification."
[ "$fail" -eq 0 ]
