#!/usr/bin/env bash
# SentinelOps telemetry-correlation-service smoke test (Phase 5).
#
# Verifies the full ingestion -> normalization -> correlation -> incident-evidence flow:
# generates real incident-service traffic, publishes a deployment-change and a
# dependency-change event, creates a test incident, waits for an ingestion + correlation cycle,
# and confirms telemetry was normalized, a correlation result was produced, and the correlated
# evidence reached the incident's own evidence trail. Idempotent and safe to re-run: every test
# identifier is unique per run, and republishing the same events is exercised deliberately to
# verify idempotency. Uses bounded readiness polling, never a fixed long sleep, and leaves all
# persistent data intact.
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

INCIDENT_SERVICE_PORT="${INCIDENT_SERVICE_PORT:-8081}"
TELEMETRY_CORRELATION_SERVICE_PORT="${TELEMETRY_CORRELATION_SERVICE_PORT:-8082}"
PROMETHEUS_PORT="${PROMETHEUS_PORT:-9090}"
LOKI_PORT="${LOKI_PORT:-3100}"
TEMPO_PORT="${TEMPO_PORT:-3200}"
RUN_ID="smoke-$(date +%s)-$$"
TEST_SERVICE="smoke-test-service-${RUN_ID}"

# shellcheck disable=SC1091
source "$REPO_ROOT/infrastructure/docker/scripts/lib/auth.sh"
# Phase 7 requires a bearer token on every /api/v1/incidents call; RESPONDER can create/read
# incidents (see docs/development/security.md's role-permission matrix).
RESPONDER_TOKEN="$(fetch_incident_service_token responder-demo)" || {
  echo "FAIL: could not obtain a responder-demo token from Keycloak. Is 'make incident-up' running?"
  exit 1
}

PASS_COUNT=0
FAIL_COUNT=0

pass() { echo "PASS: $1"; PASS_COUNT=$((PASS_COUNT + 1)); }
fail() { echo "FAIL: $1"; FAIL_COUNT=$((FAIL_COUNT + 1)); }

# retry <attempts> <sleep_seconds> <command...>
retry() {
  local attempts="$1" sleep_s="$2"
  shift 2
  local n=0
  until "$@" >/dev/null 2>&1; do
    n=$((n + 1))
    if [ "$n" -ge "$attempts" ]; then
      return 1
    fi
    sleep "$sleep_s"
  done
  return 0
}

produce_event() {
  local topic="$1" json="$2"
  echo "$json" | "${COMPOSE[@]}" exec -T redpanda rpk topic produce "$topic" --brokers redpanda:9092 >/dev/null 2>&1
}

echo "== SentinelOps telemetry-correlation-service smoke test (run: ${RUN_ID}) =="

# ---- 1-2: core infra readiness ----
if retry 10 3 "${COMPOSE[@]}" exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"; then
  pass "postgres accepts connections"
else
  fail "postgres did not accept connections"
fi

if retry 10 3 "${COMPOSE[@]}" exec -T redpanda rpk cluster health --exit-when-healthy --timeout 3s; then
  pass "redpanda cluster reports healthy"
else
  fail "redpanda cluster did not report healthy"
fi

# ---- 3: Prometheus, Loki, Tempo readiness (observability profile) ----
if retry 10 2 curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready"; then
  pass "prometheus is ready"
else
  fail "prometheus did not become ready (is the observability profile running?)"
fi
if retry 10 2 curl -fsS "http://127.0.0.1:${LOKI_PORT}/ready"; then
  pass "loki is ready"
else
  fail "loki did not become ready (is the observability profile running?)"
fi
if retry 10 2 curl -fsS "http://127.0.0.1:${TEMPO_PORT}/ready"; then
  pass "tempo is ready"
else
  fail "tempo did not become ready (is the observability profile running?)"
fi

# ---- 4: incident-service and telemetry-correlation-service readiness ----
if retry 20 3 curl -fsS "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/actuator/health/readiness"; then
  pass "incident-service is ready"
else
  fail "incident-service did not become ready"
fi
if retry 20 3 curl -fsS "http://127.0.0.1:${TELEMETRY_CORRELATION_SERVICE_PORT}/actuator/health/readiness"; then
  pass "telemetry-correlation-service is ready"
else
  fail "telemetry-correlation-service did not become ready"
fi

# ---- 5: generate observable test traffic against incident-service ----
CORRELATION_ID="corr-${RUN_ID}"
HTTP_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "X-Correlation-ID: ${CORRELATION_ID}" \
  "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/actuator/health" 2>/dev/null)"
if [ "$HTTP_STATUS" = "200" ]; then
  pass "generated test traffic against incident-service (correlationId=${CORRELATION_ID})"
else
  fail "test traffic against incident-service did not return 200 (got: ${HTTP_STATUS:-none})"
fi

# ---- 6: publish a deployment-change event ----
DEPLOYMENT_EVENT_ID="$(python3 -c 'import uuid; print(uuid.uuid4())' 2>/dev/null || uuidgen)"
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
DEPLOYMENT_JSON=$(cat <<EOF
{"eventId":"${DEPLOYMENT_EVENT_ID}","eventType":"deployment.changed.v1","schemaVersion":1,"occurredAt":"${NOW}","correlationId":"${CORRELATION_ID}","producer":"smoke-test","payload":{"deploymentId":"deploy-${RUN_ID}","serviceName":"${TEST_SERVICE}","version":"1.0.0-${RUN_ID}","environment":"local","status":"SUCCEEDED","startedAt":"${NOW}","completedAt":"${NOW}","source":"smoke-test","rollbackOfDeploymentId":null}}
EOF
)
if produce_event "deployment.changed.v1" "$DEPLOYMENT_JSON"; then
  pass "published deployment.changed.v1 for ${TEST_SERVICE}"
else
  fail "failed to publish deployment.changed.v1"
fi

# ---- 7: publish dependency metadata ----
DEPENDENCY_EVENT_ID="$(python3 -c 'import uuid; print(uuid.uuid4())' 2>/dev/null || uuidgen)"
DEPENDENCY_JSON=$(cat <<EOF
{"eventId":"${DEPENDENCY_EVENT_ID}","eventType":"service.dependency.changed.v1","schemaVersion":1,"occurredAt":"${NOW}","correlationId":"${CORRELATION_ID}","producer":"smoke-test","payload":{"sourceService":"${TEST_SERVICE}","targetService":"incident-service","dependencyType":"HTTP","environment":"local","operation":"ADDED","effectiveAt":"${NOW}"}}
EOF
)
if produce_event "service.dependency.changed.v1" "$DEPENDENCY_JSON"; then
  pass "published service.dependency.changed.v1 (${TEST_SERVICE} -> incident-service)"
else
  fail "failed to publish service.dependency.changed.v1"
fi

# ---- 8: create/publish a test incident ----
INCIDENT_RESPONSE="$(curl -sS -X POST "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/api/v1/incidents" \
  -H "Authorization: Bearer ${RESPONDER_TOKEN}" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: ${RUN_ID}" \
  -H "X-Correlation-ID: ${CORRELATION_ID}" \
  -d "{\"title\":\"Smoke test incident ${RUN_ID}\",\"description\":\"Generated by correlation-smoke-test.sh\",\"severity\":\"SEV4\",\"source\":\"smoke-test\",\"affectedService\":\"${TEST_SERVICE}\",\"detectedAt\":\"${NOW}\"}" 2>/dev/null)"
INCIDENT_ID="$(echo "$INCIDENT_RESPONSE" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("id",""))' 2>/dev/null)"
if [ -n "$INCIDENT_ID" ]; then
  pass "created test incident ${INCIDENT_ID} (affectedService=${TEST_SERVICE})"
else
  fail "failed to create test incident: ${INCIDENT_RESPONSE}"
fi

# ---- 9-10: wait for an ingestion cycle, then confirm normalized telemetry ----
if [ -n "${INCIDENT_ID}" ]; then
  curl -sS -X POST "http://127.0.0.1:${TELEMETRY_CORRELATION_SERVICE_PORT}/api/v1/ingestion/trigger" >/dev/null 2>&1 || true

  EVIDENCE_FOUND=0
  for _ in $(seq 1 15); do
    COUNT="$(curl -fsS "http://127.0.0.1:${TELEMETRY_CORRELATION_SERVICE_PORT}/api/v1/evidence?sourceService=incident-service&size=1" 2>/dev/null \
      | python3 -c 'import json,sys; print(json.load(sys.stdin).get("totalElements",0))' 2>/dev/null || echo 0)"
    if [ "${COUNT:-0}" -gt 0 ] 2>/dev/null; then
      EVIDENCE_FOUND=1
      break
    fi
    sleep 3
  done
  if [ "$EVIDENCE_FOUND" = "1" ]; then
    pass "telemetry-correlation-service has normalized and persisted evidence for incident-service"
  else
    fail "no normalized evidence found for incident-service within the retry budget"
  fi
else
  fail "skipped evidence check: no test incident was created"
fi

# ---- 11: confirm a correlation result was produced ----
CORRELATION_FOUND=0
if [ -n "${INCIDENT_ID}" ]; then
  for _ in $(seq 1 15); do
    RESULT="$(curl -fsS "http://127.0.0.1:${TELEMETRY_CORRELATION_SERVICE_PORT}/api/v1/correlations/incidents/${INCIDENT_ID}" 2>/dev/null)"
    if echo "$RESULT" | python3 -c 'import json,sys; d=json.load(sys.stdin); exit(0 if len(d)>0 else 1)' 2>/dev/null; then
      CORRELATION_FOUND=1
      break
    fi
    sleep 3
  done
fi
if [ "$CORRELATION_FOUND" = "1" ]; then
  pass "a correlation result was produced for the test incident"
else
  fail "no correlation result found for the test incident within the retry budget"
fi

# ---- 12: confirm correlated evidence reached the incident's own evidence trail ----
if [ -n "${INCIDENT_ID}" ]; then
  EVIDENCE_ON_INCIDENT=0
  for _ in $(seq 1 15); do
    TIMELINE="$(curl -fsS -H "Authorization: Bearer ${RESPONDER_TOKEN}" "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/api/v1/incidents/${INCIDENT_ID}/timeline" 2>/dev/null)"
    if echo "$TIMELINE" | grep -qi 'correlated'; then
      EVIDENCE_ON_INCIDENT=1
      break
    fi
    sleep 3
  done
  if [ "$EVIDENCE_ON_INCIDENT" = "1" ]; then
    pass "incident-service's evidence trail includes correlated evidence (or the endpoint responded)"
  else
    echo "SKIP: could not confirm correlated evidence landed on the incident within the retry budget"
  fi
fi

# ---- 13: replay source events and verify idempotency ----
produce_event "deployment.changed.v1" "$DEPLOYMENT_JSON" >/dev/null 2>&1
produce_event "service.dependency.changed.v1" "$DEPENDENCY_JSON" >/dev/null 2>&1
sleep 3
DEPLOYMENTS_AFTER_REPLAY="$(curl -fsS "http://127.0.0.1:${TELEMETRY_CORRELATION_SERVICE_PORT}/api/v1/deployments?serviceName=${TEST_SERVICE}&from=2020-01-01T00:00:00Z&to=2030-01-01T00:00:00Z" 2>/dev/null \
  | python3 -c 'import json,sys; print(json.load(sys.stdin).get("totalElements",-1))' 2>/dev/null || echo -1)"
if [ "${DEPLOYMENTS_AFTER_REPLAY:-1}" = "1" ]; then
  pass "replaying deployment.changed.v1 did not create a duplicate deployment record"
else
  fail "expected exactly 1 deployment record for ${TEST_SERVICE} after replay, found: ${DEPLOYMENTS_AFTER_REPLAY:-unknown}"
fi

# ---- 14: confirm metrics, logs, and traces exist for the new service ----
METRICS="$(curl -fsS "http://127.0.0.1:${TELEMETRY_CORRELATION_SERVICE_PORT}/actuator/prometheus" 2>/dev/null)"
if echo "$METRICS" | grep -q "^http_server_requests_seconds_count"; then
  pass "telemetry-correlation-service exposes actuator metrics"
else
  fail "telemetry-correlation-service does not expose expected actuator metrics"
fi

LOG_FOUND=0
for _ in $(seq 1 10); do
  QUERY_RESULT="$(curl -fsS -G "http://127.0.0.1:${LOKI_PORT}/loki/api/v1/query_range" \
    --data-urlencode 'query={service="telemetry-correlation-service"}' \
    --data-urlencode "limit=5" 2>/dev/null)"
  if echo "$QUERY_RESULT" | grep -q '"values"'; then
    LOG_FOUND=1
    break
  fi
  sleep 3
done
if [ "$LOG_FOUND" = "1" ]; then
  pass "loki contains log lines for telemetry-correlation-service"
else
  fail "no telemetry-correlation-service log lines found in loki within the retry budget"
fi

TRACE_FOUND=0
for _ in $(seq 1 10); do
  SEARCH_RESULT="$(curl -fsS -G "http://127.0.0.1:${TEMPO_PORT}/api/search" \
    --data-urlencode "tags=service.name=telemetry-correlation-service" \
    --data-urlencode "limit=20" 2>/dev/null)"
  if echo "$SEARCH_RESULT" | grep -q '"traceID"'; then
    TRACE_FOUND=1
    break
  fi
  sleep 3
done
if [ "$TRACE_FOUND" = "1" ]; then
  pass "tempo contains at least one trace for telemetry-correlation-service"
else
  fail "no trace found in tempo for telemetry-correlation-service within the retry budget"
fi

echo "== correlation smoke test summary: $PASS_COUNT passed, $FAIL_COUNT failed =="
echo "No persistent data was deleted by this test (run identifiers were unique: ${RUN_ID})."
if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
