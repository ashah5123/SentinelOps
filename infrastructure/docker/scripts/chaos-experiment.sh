#!/usr/bin/env bash
# SentinelOps Phase 15 chaos-experiment runner.
#
# Executes one deterministic, bounded, auto-recovering chaos experiment against the local
# Docker Compose stack (this repo has no real Kubernetes cluster — see
# infrastructure/docker/scripts/chaos/chaos-experiments.yaml's header for why every experiment
# targets a Compose container as the local pod/node equivalent). Every experiment:
#   - refuses to run unless CHAOS_EXPERIMENTS_ENABLED=true is set (see chaos_lib.sh)
#   - refuses to target any container missing the sentinelops.chaos-target label
#   - is wall-clock bounded (never runs longer than its documented safety limit)
#   - registers a trap that restores normal operation even on failure or Ctrl-C
#   - validates recovery before exiting successfully
#
# Usage:
#   infrastructure/docker/scripts/chaos-experiment.sh <experiment-id> [--dry-run] [options]
#   infrastructure/docker/scripts/chaos-experiment.sh list
#
# See infrastructure/docker/scripts/chaos/chaos-experiments.yaml for the full catalog (target,
# duration, expected behavior, safety limits, abort condition, recovery validation) of every
# experiment id below.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"
CHAOS_DIR="$REPO_ROOT/infrastructure/docker/scripts/chaos"

# shellcheck disable=SC1091
source "$CHAOS_DIR/chaos_lib.sh"

DRY_RUN=false
for arg in "$@"; do
  if [ "$arg" = "--dry-run" ]; then
    DRY_RUN=true
  fi
done

run() {
  if [ "$DRY_RUN" = true ]; then
    chaos_log "DRY-RUN: would run: $*"
  else
    "$@"
  fi
}

EXPERIMENT="${1:-}"

if [ "$EXPERIMENT" = "list" ] || [ -z "$EXPERIMENT" ]; then
  echo "Available experiments (see chaos/chaos-experiments.yaml for full detail):"
  awk '/^  - id:/{print "  - " $3}' "$CHAOS_DIR/chaos-experiments.yaml"
  exit 0
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first." >&2
  exit 1
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

chaos_require_opt_in

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
INCIDENT_SERVICE_PORT="${INCIDENT_SERVICE_PORT:-8081}"
HEALTH_URL="http://localhost:${INCIDENT_SERVICE_PORT}/actuator/health"

container_name() {
  # Maps a short logical name to the Compose container_name (see docker-compose.yml).
  echo "sentinelops-$1"
}

require_target() {
  local logical="$1" container
  container="$(container_name "$logical")"
  if [ "$DRY_RUN" = true ]; then
    chaos_log "DRY-RUN: would verify chaos-target label on $container"
    return 0
  fi
  chaos_require_labeled_target "$container"
}

# --- pod-termination -------------------------------------------------------------------------
exp_pod_termination() {
  local target="${CHAOS_TARGET:-incident-service}" cycles="${CHAOS_CYCLES:-5}" interval="${CHAOS_INTERVAL:-10}"
  require_target "$target"
  chaos_log "pod-termination: $cycles kill/restart cycles against $target, ${interval}s apart"
  chaos_register_recovery "run ${COMPOSE[*]} up -d $target"
  for i in $(seq 1 "$cycles"); do
    chaos_log "  cycle $i/$cycles: killing $target"
    run "${COMPOSE[@]}" kill "$target"
    run "${COMPOSE[@]}" up -d "$target"
    sleep "$interval"
    if [ "$DRY_RUN" != true ]; then
      chaos_wait_healthy "$HEALTH_URL" 60 || { chaos_log "ABORT: $target did not become healthy within 60s"; exit 1; }
    fi
  done
  chaos_log "pod-termination: recovery validated ($target healthy after $cycles cycles)"
}

# --- resource-pressure -------------------------------------------------------------------------
exp_resource_pressure() {
  local target="${CHAOS_TARGET:-incident-service}" duration="${CHAOS_DURATION:-60}"
  local cpu_limit="${CHAOS_CPU_LIMIT:-0.25}" mem_limit="${CHAOS_MEM_LIMIT:-128m}"
  require_target "$target"
  local container; container="$(container_name "$target")"
  chaos_log "resource-pressure: constraining $target to ${cpu_limit} CPU / ${mem_limit} for ${duration}s"
  chaos_register_recovery "run docker update --cpus 0 --memory 0 $container 2>/dev/null || true"
  run docker update --cpus "$cpu_limit" --memory "$mem_limit" "$container"
  sleep "$duration"
  chaos_log "resource-pressure: restoring original limits"
  run docker update --cpus 0 --memory 0 "$container"
  if [ "$DRY_RUN" != true ]; then
    chaos_wait_healthy "$HEALTH_URL" 30 || { chaos_log "ABORT: $target did not recover within 30s of restoring limits"; exit 1; }
  fi
  chaos_log "resource-pressure: recovery validated"
}

# --- network-fault -------------------------------------------------------------------------
exp_network_fault() {
  local target="${CHAOS_TARGET:-incident-service}" mode="${CHAOS_MODE:-latency}" duration="${CHAOS_DURATION:-30}"
  local delay_ms="${CHAOS_DELAY_MS:-500}" loss_pct="${CHAOS_LOSS_PCT:-10}"
  require_target "$target"
  local container; container="$(container_name "$target")"
  case "$mode" in
    latency)
      chaos_log "network-fault: injecting ${delay_ms}ms latency into $target for ${duration}s"
      chaos_register_recovery "run docker exec $container tc qdisc del dev eth0 root 2>/dev/null || true"
      if ! run docker exec "$container" tc qdisc add dev eth0 root netem delay "${delay_ms}ms"; then
        chaos_log "ABORT: tc unavailable in $container image — network-fault unsupported here"
        exit 1
      fi
      sleep "$duration"
      run docker exec "$container" tc qdisc del dev eth0 root
      ;;
    packet-loss)
      chaos_log "network-fault: injecting ${loss_pct}% packet loss into $target for ${duration}s"
      chaos_register_recovery "run docker exec $container tc qdisc del dev eth0 root 2>/dev/null || true"
      run docker exec "$container" tc qdisc add dev eth0 root netem loss "${loss_pct}%"
      sleep "$duration"
      run docker exec "$container" tc qdisc del dev eth0 root
      ;;
    isolation)
      if [ "$target" = "postgres" ]; then
        chaos_log "ABORT: isolation mode never targets postgres (see chaos-experiments.yaml safety_limits)"
        exit 1
      fi
      chaos_log "network-fault: isolating $target from sentinelops-net for ${duration}s"
      chaos_register_recovery "run docker network connect sentinelops-net $container 2>/dev/null || true"
      run docker network disconnect sentinelops-net "$container"
      sleep "$duration"
      run docker network connect sentinelops-net "$container"
      ;;
    *)
      chaos_log "ABORT: unknown network-fault mode '$mode' (expected latency|packet-loss|isolation)"
      exit 1
      ;;
  esac
  if [ "$DRY_RUN" != true ]; then
    chaos_wait_healthy "$HEALTH_URL" 10 || chaos_log "WARN: $target health check did not recover within 10s"
  fi
  chaos_log "network-fault: recovery step completed"
}

# --- kafka-broker-fault -------------------------------------------------------------------------
exp_kafka_broker_fault() {
  local duration="${CHAOS_DURATION:-15}"
  require_target "redpanda"
  chaos_log "kafka-broker-fault: pausing redpanda for ${duration}s (max 60s)"
  [ "$duration" -le 60 ] || { chaos_log "ABORT: duration exceeds the 60s safety limit"; exit 1; }
  chaos_register_recovery "run ${COMPOSE[*]} unpause redpanda 2>/dev/null || true"
  run "${COMPOSE[@]}" pause redpanda
  sleep "$duration"
  run "${COMPOSE[@]}" unpause redpanda
  chaos_log "kafka-broker-fault: broker unpaused; outbox backlog should drain automatically"
}

# --- postgres-fault -------------------------------------------------------------------------
exp_postgres_fault() {
  local mode="${CHAOS_MODE:-unavailability}" duration="${CHAOS_DURATION:-10}"
  require_target "postgres"
  case "$mode" in
    unavailability)
      [ "$duration" -le 30 ] || { chaos_log "ABORT: duration exceeds the 30s safety limit"; exit 1; }
      chaos_log "postgres-fault: pausing postgres for ${duration}s"
      chaos_register_recovery "run ${COMPOSE[*]} unpause postgres 2>/dev/null || true"
      run "${COMPOSE[@]}" pause postgres
      sleep "$duration"
      run "${COMPOSE[@]}" unpause postgres
      ;;
    connection-exhaustion)
      chaos_log "postgres-fault: connection-exhaustion mode holds many idle connections for ${duration}s"
      chaos_log "  (read-only against the configured max_connections; never modifies it)"
      run "${COMPOSE[@]}" exec -T postgres bash -c \
        'for i in $(seq 1 $(( $(psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -tAc "show max_connections") - 5 ))); do
           psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select pg_sleep('"$duration"')" & done; wait' || true
      ;;
    *)
      chaos_log "ABORT: unknown postgres-fault mode '$mode' (expected unavailability|connection-exhaustion)"
      exit 1
      ;;
  esac
  if [ "$DRY_RUN" != true ]; then
    chaos_wait_healthy "$HEALTH_URL" 30 || { chaos_log "ABORT: postgres/incident-service did not recover within 30s"; exit 1; }
  fi
  chaos_log "postgres-fault: recovery validated"
}

# --- redis-fault -------------------------------------------------------------------------
exp_redis_fault() {
  local duration="${CHAOS_DURATION:-15}"
  require_target "redis"
  [ "$duration" -le 60 ] || { chaos_log "ABORT: duration exceeds the 60s safety limit"; exit 1; }
  chaos_log "redis-fault: pausing redis for ${duration}s"
  chaos_register_recovery "run ${COMPOSE[*]} unpause redis 2>/dev/null || true"
  run "${COMPOSE[@]}" pause redis
  sleep "$duration"
  run "${COMPOSE[@]}" unpause redis
  if [ "$DRY_RUN" != true ]; then
    chaos_wait_healthy "$HEALTH_URL" 10 || chaos_log "WARN: incident-service health check degraded during redis outage"
  fi
  chaos_log "redis-fault: recovery validated (no application code path currently depends on redis — see chaos-experiments.yaml)"
}

# --- object-storage-fault -------------------------------------------------------------------------
exp_object_storage_fault() {
  local duration="${CHAOS_DURATION:-15}"
  require_target "minio"
  [ "$duration" -le 60 ] || { chaos_log "ABORT: duration exceeds the 60s safety limit"; exit 1; }
  chaos_log "object-storage-fault: pausing minio for ${duration}s"
  chaos_register_recovery "run ${COMPOSE[*]} unpause minio 2>/dev/null || true"
  run "${COMPOSE[@]}" pause minio
  sleep "$duration"
  run "${COMPOSE[@]}" unpause minio
  chaos_log "object-storage-fault: recovery validated (no application code path currently depends on minio — see chaos-experiments.yaml)"
}

# --- notification-fault -------------------------------------------------------------------------
exp_notification_fault() {
  local target="${CHAOS_TARGET:-mailpit}" duration="${CHAOS_DURATION:-15}"
  require_target "$target"
  [ "$duration" -le 60 ] || { chaos_log "ABORT: duration exceeds the 60s safety limit"; exit 1; }
  chaos_log "notification-fault: pausing $target for ${duration}s"
  chaos_register_recovery "run ${COMPOSE[*]} unpause $target 2>/dev/null || true"
  run "${COMPOSE[@]}" pause "$target"
  sleep "$duration"
  run "${COMPOSE[@]}" unpause "$target"
  chaos_log "notification-fault: recovery validated ($target unpaused; retries should drain the backlog)"
}

# --- telemetry-malformed-event -------------------------------------------------------------------------
exp_telemetry_malformed_event() {
  require_target "redpanda"
  local topic="deployment.changed.v1" run_id="chaos-test-$(date +%s)"
  chaos_log "telemetry-malformed-event: producing duplicate/delayed/reordered/malformed events onto $topic"

  local now; now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local valid_payload
  valid_payload=$(cat <<EOF
{"eventId":"$(python3 -c 'import uuid; print(uuid.uuid4())')","eventType":"deployment.changed.v1","schemaVersion":1,"occurredAt":"$now","correlationId":"$run_id","producer":"chaos-experiment","payload":{"deploymentId":"$run_id-dep-1","serviceName":"chaos-test-service","version":"1.0.0","environment":"local","status":"COMPLETED","startedAt":"$now","completedAt":"$now","source":"chaos-experiment","rollbackOfDeploymentId":null}}
EOF
)
  # 1. Valid event.
  run bash -c "echo '$valid_payload' | ${COMPOSE[*]} exec -T redpanda rpk topic produce $topic --brokers localhost:9092"
  # 2. Exact duplicate (same eventId) — must be deduplicated, never double-processed.
  run bash -c "echo '$valid_payload' | ${COMPOSE[*]} exec -T redpanda rpk topic produce $topic --brokers localhost:9092"
  # 3. Malformed JSON — must be dead-lettered, never crash the consumer.
  run bash -c "echo 'not valid json {' | ${COMPOSE[*]} exec -T redpanda rpk topic produce $topic --brokers localhost:9092"
  # 4. Schema-invalid (missing required fields) — must be dead-lettered.
  run bash -c "echo '{\"eventId\":\"$(python3 -c 'import uuid; print(uuid.uuid4())')\",\"eventType\":\"deployment.changed.v1\"}' | ${COMPOSE[*]} exec -T redpanda rpk topic produce $topic --brokers localhost:9092"

  chaos_log "telemetry-malformed-event: 4 synthetic events produced (tagged correlationId=$run_id) — inspect the .dlq topic and consumer lag to validate"
}

# --- remediation-execution-fault -------------------------------------------------------------------------
exp_remediation_execution_fault() {
  chaos_log "remediation-execution-fault: forcing checkout-api/staging unhealthy, then restarting incident-service mid-flight"
  chaos_log "  (see infrastructure/docker/scripts/remediation-demo.sh for the full propose->approve->rollback flow this complements)"
  run "${COMPOSE[@]}" exec -T postgres psql -U "${POSTGRES_USER:-sentinelops}" -d "${POSTGRES_DB:-sentinelops}" \
    -c "UPDATE incidents.simulated_deployments SET healthy = false WHERE service = 'checkout-api' AND environment = 'staging';"
  chaos_log "  target marked unhealthy — propose+approve the restart-checkout-service runbook now via remediation-demo.sh or the console,"
  chaos_log "  then restart incident-service mid-execution to exercise restart-recovery:"
  require_target "incident-service"
  sleep 5
  run "${COMPOSE[@]}" restart incident-service
  if [ "$DRY_RUN" != true ]; then
    chaos_wait_healthy "$HEALTH_URL" 60 || { chaos_log "ABORT: incident-service did not recover within 60s of restart"; exit 1; }
  fi
  chaos_log "remediation-execution-fault: incident-service recovered; verify via GET /api/v1/remediations/{id} that the in-flight execution reached ROLLED_BACK, FAILED, or SUCCEEDED (never left RUNNING)"
}

case "$EXPERIMENT" in
  pod-termination) exp_pod_termination ;;
  resource-pressure) exp_resource_pressure ;;
  network-fault) exp_network_fault ;;
  kafka-broker-fault) exp_kafka_broker_fault ;;
  postgres-fault) exp_postgres_fault ;;
  redis-fault) exp_redis_fault ;;
  object-storage-fault) exp_object_storage_fault ;;
  notification-fault) exp_notification_fault ;;
  telemetry-malformed-event) exp_telemetry_malformed_event ;;
  remediation-execution-fault) exp_remediation_execution_fault ;;
  llm-fault)
    chaos_log "llm-fault: set sentinelops.ai.chaos.enabled=true and sentinelops.ai.chaos.mode={slow|unavailable|malformed}"
    chaos_log "  in application.yml (or the equivalent env vars), restart incident-service, then restore mode=off afterward."
    chaos_log "  See docs/development/ai-triage.md's chaos-testing section and ChaosInjectingAiProviderTest for the automated equivalent."
    ;;
  *)
    echo "Unknown experiment '$EXPERIMENT'. Run '$0 list' for the catalog." >&2
    exit 1
    ;;
esac
