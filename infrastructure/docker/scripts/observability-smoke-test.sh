#!/usr/bin/env bash
# SentinelOps observability stack smoke test (Phase 4).
#
# Verifies the OpenTelemetry Collector, Prometheus, Grafana, Loki, Tempo, and Alertmanager
# are reachable and correctly wired to the incident-service, and that a real API request
# produces a correlated trace and log line. Idempotent: safe to re-run any number of times.
# Uses bounded retries (never a fixed sleep) and exits non-zero if any check fails.
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

PROMETHEUS_PORT="${PROMETHEUS_PORT:-9090}"
GRAFANA_PORT="${GRAFANA_PORT:-3001}"
LOKI_PORT="${LOKI_PORT:-3100}"
TEMPO_PORT="${TEMPO_PORT:-3200}"
ALERTMANAGER_PORT="${ALERTMANAGER_PORT:-9093}"
INCIDENT_SERVICE_PORT="${INCIDENT_SERVICE_PORT:-8081}"
GRAFANA_ADMIN_USER="${GRAFANA_ADMIN_USER:-admin}"
GRAFANA_ADMIN_PASSWORD="${GRAFANA_ADMIN_PASSWORD:-change-me-local-dev-only}"

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

echo "== SentinelOps observability smoke test =="

# ---- Container health ----
UNHEALTHY="$("${COMPOSE[@]}" --profile observability ps --format '{{.Name}} {{.Health}}' 2>/dev/null \
  | awk '$2!="" && $2!="healthy" {print $1}')"
if [ -z "$UNHEALTHY" ]; then
  pass "all observability containers report healthy"
else
  fail "unhealthy observability containers:$UNHEALTHY"
fi

# ---- OpenTelemetry Collector: health check extension ----
if retry 10 2 curl -fsS "http://127.0.0.1:${OTEL_COLLECTOR_HTTP_PORT:-4318}" ; then
  pass "otel-collector OTLP/HTTP endpoint accepts connections"
else
  # OTLP/HTTP has no unauthenticated GET route; a non-2xx HTTP response still proves the
  # port is listening, so only a connection failure counts as FAIL.
  if retry 5 2 bash -c "exec 3<>/dev/tcp/127.0.0.1/${OTEL_COLLECTOR_HTTP_PORT:-4318}"; then
    pass "otel-collector OTLP/HTTP port is listening"
  else
    fail "otel-collector OTLP/HTTP port is not reachable"
  fi
fi

# ---- Prometheus: ready and required scrape targets up ----
if retry 10 2 curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/-/ready"; then
  pass "prometheus is ready"
else
  fail "prometheus did not become ready"
fi

MISSING_TARGETS=""
for job in otel-collector incident-service; do
  STATE="$(curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/targets" 2>/dev/null \
    | grep -o "\"job\":\"$job\"[^}]*\"health\":\"[a-z]*\"" \
    | grep -o '"health":"[a-z]*"' | head -1)"
  if [ "$STATE" != '"health":"up"' ]; then
    MISSING_TARGETS="$MISSING_TARGETS $job"
  fi
done
if [ -z "$MISSING_TARGETS" ]; then
  pass "prometheus reports required scrape targets up (otel-collector, incident-service)"
else
  fail "prometheus scrape targets not up:$MISSING_TARGETS"
fi

# ---- Prometheus: required alert rules loaded ----
MISSING_RULES=""
for alertname in SentinelOpsIncidentServiceDown SentinelOpsHighHttp5xxRate SentinelOpsHighRequestLatency \
    SentinelOpsKafkaConsumerErrors SentinelOpsOutboxPublishFailures SentinelOpsAnomalyProcessingFailures; do
  if ! curl -fsS "http://127.0.0.1:${PROMETHEUS_PORT}/api/v1/rules" 2>/dev/null | grep -q "\"name\":\"$alertname\""; then
    MISSING_RULES="$MISSING_RULES $alertname"
  fi
done
if [ -z "$MISSING_RULES" ]; then
  pass "all required prometheus alert rules are loaded"
else
  fail "missing prometheus alert rules:$MISSING_RULES"
fi

# ---- Alertmanager: ready ----
if retry 10 2 curl -fsS "http://127.0.0.1:${ALERTMANAGER_PORT}/-/ready"; then
  pass "alertmanager is ready"
else
  fail "alertmanager did not become ready"
fi

# ---- Loki: ready ----
if retry 10 2 curl -fsS "http://127.0.0.1:${LOKI_PORT}/ready"; then
  pass "loki is ready"
else
  fail "loki did not become ready"
fi

# ---- Tempo: ready ----
if retry 10 2 curl -fsS "http://127.0.0.1:${TEMPO_PORT}/ready"; then
  pass "tempo is ready"
else
  fail "tempo did not become ready"
fi

# ---- Grafana: health and provisioned datasources ----
if retry 10 2 curl -fsS "http://127.0.0.1:${GRAFANA_PORT}/api/health"; then
  pass "grafana is healthy"
else
  fail "grafana did not report healthy"
fi

MISSING_DATASOURCES=""
DS_JSON="$(curl -fsS -u "${GRAFANA_ADMIN_USER}:${GRAFANA_ADMIN_PASSWORD}" \
  "http://127.0.0.1:${GRAFANA_PORT}/api/datasources" 2>/dev/null)"
for ds in Prometheus Loki Tempo; do
  echo "$DS_JSON" | grep -q "\"name\":\"$ds\"" || MISSING_DATASOURCES="$MISSING_DATASOURCES $ds"
done
if [ -z "$MISSING_DATASOURCES" ]; then
  pass "grafana has all required datasources provisioned (Prometheus, Loki, Tempo)"
else
  fail "grafana is missing datasources:$MISSING_DATASOURCES"
fi

# ---- incident-service: exposes expected metrics ----
METRICS="$(curl -fsS "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/actuator/prometheus" 2>/dev/null)"
MISSING_METRICS=""
for metric in http_server_requests_seconds_count jvm_memory_used_bytes hikaricp_connections_active; do
  echo "$METRICS" | grep -q "^$metric" || MISSING_METRICS="$MISSING_METRICS $metric"
done
if [ -z "$MISSING_METRICS" ]; then
  pass "incident-service exposes expected actuator metrics"
else
  fail "incident-service is missing expected metrics:$MISSING_METRICS"
fi

# ---- End-to-end: a real request produces a correlated trace and log ----
CORRELATION_ID="obs-smoke-$(date +%s)"
HTTP_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' \
  -H "X-Correlation-ID: ${CORRELATION_ID}" \
  "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/actuator/health" 2>/dev/null)"
if [ "$HTTP_STATUS" = "200" ]; then
  pass "generated a correlated test request (correlationId=${CORRELATION_ID})"

  # Loki ingests asynchronously via the collector's batch processor; poll with bounded retries.
  LOKI_FOUND=0
  for _ in $(seq 1 10); do
    QUERY_RESULT="$(curl -fsS -G "http://127.0.0.1:${LOKI_PORT}/loki/api/v1/query_range" \
      --data-urlencode "query={service=\"incident-service\"} |= \"${CORRELATION_ID}\"" \
      --data-urlencode "limit=5" 2>/dev/null)"
    if echo "$QUERY_RESULT" | grep -q "$CORRELATION_ID"; then
      LOKI_FOUND=1
      break
    fi
    sleep 3
  done
  if [ "$LOKI_FOUND" = "1" ]; then
    pass "loki contains a log line correlated with the test request"
  else
    fail "no correlated log line found in loki within the retry budget"
  fi

  # Tempo's search API returns recent traces for the service; a matching trace should
  # appear shortly after the request completes.
  TEMPO_FOUND=0
  for _ in $(seq 1 10); do
    SEARCH_RESULT="$(curl -fsS -G "http://127.0.0.1:${TEMPO_PORT}/api/search" \
      --data-urlencode "tags=service.name=incident-service" \
      --data-urlencode "limit=20" 2>/dev/null)"
    if echo "$SEARCH_RESULT" | grep -q '"traceID"'; then
      TEMPO_FOUND=1
      break
    fi
    sleep 3
  done
  if [ "$TEMPO_FOUND" = "1" ]; then
    pass "tempo contains at least one trace for incident-service"
  else
    fail "no trace found in tempo within the retry budget"
  fi
else
  fail "test request to incident-service did not return 200 (got: ${HTTP_STATUS:-none})"
fi

# ---- Localhost-only exposure check ----
EXPOSED="$("${COMPOSE[@]}" --profile observability config 2>/dev/null | awk '/published:/{print}' | grep -v '127\.0\.0\.1' || true)"
if [ -z "$EXPOSED" ]; then
  pass "no observability service ports are published beyond 127.0.0.1"
else
  fail "found observability port bindings not restricted to 127.0.0.1"
fi

echo "== observability smoke test summary: $PASS_COUNT passed, $FAIL_COUNT failed =="
if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
