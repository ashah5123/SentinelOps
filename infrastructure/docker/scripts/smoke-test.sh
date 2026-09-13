#!/usr/bin/env bash
# SentinelOps local platform smoke test (Phase 2).
#
# Verifies the local data and event-streaming infrastructure is reachable,
# correctly configured, and not unexpectedly exposed beyond localhost.
# Exits non-zero if any check fails. Never prints credential values.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"
NETWORK="sentinelops-net"
MC_IMAGE="minio/mc:RELEASE.2024-10-02T08-27-28Z"

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first."
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

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

echo "== SentinelOps platform smoke test =="

# ---- PostgreSQL: connectivity ----
if retry 10 3 "${COMPOSE[@]}" exec -T postgres pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB"; then
  pass "postgres accepts connections"
else
  fail "postgres did not accept connections"
fi

# ---- PostgreSQL: pgvector extension ----
if "${COMPOSE[@]}" exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
    "SELECT 1 FROM pg_extension WHERE extname='vector';" 2>/dev/null | grep -q 1; then
  pass "pgvector extension is enabled"
else
  fail "pgvector extension is not enabled"
fi

# ---- PostgreSQL: required schemas ----
MISSING_SCHEMAS=""
for schema in incidents audit runbooks; do
  if ! "${COMPOSE[@]}" exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc \
      "SELECT 1 FROM information_schema.schemata WHERE schema_name='$schema';" 2>/dev/null | grep -q 1; then
    MISSING_SCHEMAS="$MISSING_SCHEMAS $schema"
  fi
done
if [ -z "$MISSING_SCHEMAS" ]; then
  pass "required postgres schemas exist (incidents, audit, runbooks)"
else
  fail "missing postgres schemas:$MISSING_SCHEMAS"
fi

# ---- Redis: authentication and PING ----
if "${COMPOSE[@]}" exec -T redis redis-cli -a "$REDIS_PASSWORD" --no-auth-warning ping 2>/dev/null | grep -q PONG; then
  pass "redis authenticates and responds to PING"
else
  fail "redis authentication or PING failed"
fi

# ---- Redpanda: cluster health (Kafka API reachable) ----
if retry 10 3 "${COMPOSE[@]}" exec -T redpanda rpk cluster health --exit-when-healthy --timeout 3s; then
  pass "redpanda cluster reports healthy"
else
  fail "redpanda cluster did not report healthy"
fi

# ---- Redpanda: required topics ----
REQUIRED_TOPICS="incident.detected.v1 telemetry.anomaly.v1 deployment.changed.v1 remediation.requested.v1 remediation.completed.v1 audit.event.v1"
EXISTING_TOPICS="$("${COMPOSE[@]}" exec -T redpanda rpk topic list 2>/dev/null | awk 'NR>1{print $1}')"
MISSING_TOPICS=""
for topic in $REQUIRED_TOPICS; do
  echo "$EXISTING_TOPICS" | grep -qx "$topic" || MISSING_TOPICS="$MISSING_TOPICS $topic"
done
if [ -z "$MISSING_TOPICS" ]; then
  pass "all required topics exist"
else
  fail "missing topics:$MISSING_TOPICS"
fi

# ---- Redpanda Console (optional profile) ----
CONSOLE_PORT="${REDPANDA_CONSOLE_PORT:-8080}"
if "${COMPOSE[@]}" --profile console ps redpanda-console 2>/dev/null | grep -q redpanda-console; then
  if retry 10 2 curl -fsS "http://127.0.0.1:${CONSOLE_PORT}/"; then
    pass "redpanda console responds on localhost"
  else
    fail "redpanda console did not respond"
  fi
else
  echo "SKIP: redpanda console is not running (optional 'console' profile not enabled)"
fi

# ---- MinIO: API reachable ----
MINIO_API_PORT="${MINIO_API_PORT:-9000}"
if retry 10 2 curl -fsS "http://127.0.0.1:${MINIO_API_PORT}/minio/health/live"; then
  pass "minio API responds"
else
  fail "minio API did not respond"
fi

# ---- MinIO: required buckets ----
MISSING_BUCKETS=""
for bucket in runbooks incident-artifacts postmortems; do
  if ! docker run --rm --network "$NETWORK" \
      -e "MC_HOST_local=http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000" \
      "$MC_IMAGE" ls "local/$bucket" >/dev/null 2>&1; then
    MISSING_BUCKETS="$MISSING_BUCKETS $bucket"
  fi
done
if [ -z "$MISSING_BUCKETS" ]; then
  pass "all required minio buckets exist"
else
  fail "missing minio buckets:$MISSING_BUCKETS"
fi

# ---- Container health ----
UNHEALTHY="$("${COMPOSE[@]}" --profile console ps --format '{{.Name}} {{.Health}}' 2>/dev/null | awk '$2!="" && $2!="healthy" {print $1}')"
if [ -z "$UNHEALTHY" ]; then
  pass "all long-running containers report healthy"
else
  fail "unhealthy containers:$UNHEALTHY"
fi

# ---- Localhost-only exposure check ----
EXPOSED="$("${COMPOSE[@]}" config 2>/dev/null | awk '/published:/{print}' | grep -v '127\.0\.0\.1' || true)"
if [ -z "$EXPOSED" ]; then
  pass "no service ports are published beyond 127.0.0.1"
else
  fail "found port bindings not restricted to 127.0.0.1"
fi

echo "== smoke test summary: $PASS_COUNT passed, $FAIL_COUNT failed =="
if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
exit 0
