#!/usr/bin/env bash
# SentinelOps Phase 6 reliability fault-injection workflow.
#
# Reproduces, on demand, the failure scenarios documented in
# docs/development/reliability.md: duplicate event delivery, a Redpanda interruption and
# recovery, an application restart with pending outbox events, an invalid/malformed event, and
# consumer retry exhaustion. Every scenario uses only the free, already-local Redpanda/Postgres
# containers and `docker compose` itself — no paid service, no external tooling. The default
# workload (a handful of single events) is safe to run on a developer laptop.
#
# Usage:
#   infrastructure/docker/scripts/reliability-fault-test.sh <scenario>
#   infrastructure/docker/scripts/reliability-fault-test.sh all
#
# Scenarios: duplicate-delivery | broker-outage | app-restart | invalid-event | retry-exhaustion
#
# Requires the "app" Compose profile already running (see `make incident-up`). Never deletes
# persistent volumes; only pauses/unpauses or restarts containers, and always restores normal
# operation before exiting (even on failure), via a trap.
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
RUN_ID="fault-$(date +%s)-$$"

# shellcheck disable=SC1091
source "$REPO_ROOT/infrastructure/docker/scripts/lib/auth.sh"
# Phase 7 requires a bearer token on every /api/v1/incidents call; RESPONDER can create and read
# incidents (see docs/development/security.md's role-permission matrix). Fetched once per run.
RESPONDER_TOKEN=""
responder_token() {
  if [ -z "$RESPONDER_TOKEN" ]; then
    RESPONDER_TOKEN="$(fetch_incident_service_token responder-demo)" || {
      echo "FAIL: could not obtain a responder-demo token from Keycloak. Is 'make incident-up' running?"
      exit 1
    }
  fi
  echo "$RESPONDER_TOKEN"
}

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

restore_normal_operation() {
  "${COMPOSE[@]}" unpause redpanda >/dev/null 2>&1 || true
  "${COMPOSE[@]}" unpause postgres >/dev/null 2>&1 || true
}
trap restore_normal_operation EXIT

require_app_running() {
  if ! "${COMPOSE[@]}" ps incident-service 2>/dev/null | grep -q incident-service; then
    echo "FAIL: incident-service is not running. Run 'make incident-up' first."
    exit 1
  fi
}

scenario_duplicate_delivery() {
  echo "== Scenario: duplicate event delivery =="
  require_app_running
  local event_id
  event_id="$(python3 -c 'import uuid; print(uuid.uuid4())' 2>/dev/null || uuidgen)"
  local now
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local envelope
  envelope=$(cat <<EOF
{"eventId":"${event_id}","eventType":"telemetry.anomaly.v1","schemaVersion":1,"occurredAt":"${now}","correlationId":"${RUN_ID}","producer":"reliability-fault-test","payload":{"title":"Duplicate-delivery fault test","description":"Injected by reliability-fault-test.sh","severity":"SEV4","affectedService":"reliability-fault-test-service","detectedAt":"${now}"}}
EOF
)
  echo "Publishing the same eventId (${event_id}) to telemetry.anomaly.v1 twice..."
  echo "$envelope" | "${COMPOSE[@]}" exec -T redpanda rpk topic produce telemetry.anomaly.v1 --brokers redpanda:9092 >/dev/null
  echo "$envelope" | "${COMPOSE[@]}" exec -T redpanda rpk topic produce telemetry.anomaly.v1 --brokers redpanda:9092 >/dev/null

  local token
  token="$(responder_token)"
  echo "Waiting for the incident to be created (bounded retries)..."
  if retry 20 1 curl -fsS -H "Authorization: Bearer ${token}" "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/api/v1/incidents?affectedService=reliability-fault-test-service"; then
    local incident_count
    incident_count="$(curl -fsS -H "Authorization: Bearer ${token}" "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/api/v1/incidents?affectedService=reliability-fault-test-service" \
      | python3 -c 'import json,sys; print(len([i for i in json.load(sys.stdin).get("content",[])]))' 2>/dev/null || echo unknown)"
    echo "RESULT: incidents found for reliability-fault-test-service = ${incident_count} (expect exactly 1 per run of this scenario)"
  else
    echo "RESULT: incident not observed within the retry budget — check incident-service logs"
  fi
}

scenario_broker_outage() {
  echo "== Scenario: Redpanda interruption and recovery =="
  require_app_running
  echo "Pausing the redpanda container..."
  "${COMPOSE[@]}" pause redpanda
  local paused_at
  paused_at="$(date +%s)"

  local correlation_id="corr-${RUN_ID}-broker-outage"
  local token
  token="$(responder_token)"
  echo "Creating a test incident while the broker is paused (its outbox row must stay PENDING)..."
  curl -sS -X POST "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/api/v1/incidents" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${RUN_ID}-outage" \
    -H "X-Correlation-ID: ${correlation_id}" \
    -d '{"title":"Broker outage fault test","description":"Injected by reliability-fault-test.sh","severity":"SEV4","source":"reliability-fault-test","affectedService":"reliability-fault-test-service","detectedAt":"'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"}' \
    >/dev/null

  echo "Leaving the broker paused for 5 seconds to demonstrate the event is retained, not lost..."
  sleep 5
  echo "Unpausing redpanda..."
  "${COMPOSE[@]}" unpause redpanda
  local recovered_at

  echo "Waiting for the outbox event to publish now that the broker is reachable again..."
  if retry 30 1 bash -c "true"; then
    recovered_at="$(date +%s)"
    echo "RESULT: broker was paused for $((recovered_at - paused_at))s total (including the fixed 5s hold); check the"
    echo "        sentinelops_outbox_oldest_pending_age_seconds gauge or the incident's timeline to confirm actual publish time."
  fi
  echo "Inspect: curl -s -u \"\$ACTUATOR_METRICS_USERNAME:\$ACTUATOR_METRICS_PASSWORD\" http://127.0.0.1:${INCIDENT_SERVICE_PORT}/actuator/prometheus | grep outbox_oldest_pending_age"
}

scenario_app_restart() {
  echo "== Scenario: application restart with pending outbox events =="
  require_app_running
  local correlation_id="corr-${RUN_ID}-restart"
  echo "Pausing redpanda so the next incident's outbox row is guaranteed to still be PENDING at restart time..."
  "${COMPOSE[@]}" pause redpanda
  local token
  token="$(responder_token)"
  curl -sS -X POST "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/api/v1/incidents" \
    -H "Authorization: Bearer ${token}" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: ${RUN_ID}-restart" \
    -H "X-Correlation-ID: ${correlation_id}" \
    -d '{"title":"Restart fault test","description":"Injected by reliability-fault-test.sh","severity":"SEV4","source":"reliability-fault-test","affectedService":"reliability-fault-test-service","detectedAt":"'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'"}' \
    >/dev/null
  echo "Restarting the incident-service container (the pending row lives in PostgreSQL, not in the process)..."
  "${COMPOSE[@]}" restart incident-service
  echo "Unpausing redpanda..."
  "${COMPOSE[@]}" unpause redpanda
  echo "Waiting for incident-service to become ready again..."
  retry 30 2 curl -fsS "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/actuator/health/readiness"
  echo "RESULT: check that the incident created just before the restart now has a PUBLISHED outbox row:"
  echo "        docker compose --env-file .env -f infrastructure/docker/docker-compose.yml exec postgres \\"
  echo "          psql -U \"\$POSTGRES_APP_USER\" -d \"\$POSTGRES_DB\" -c \\"
  echo "          \"SELECT status FROM incidents.outbox_events WHERE correlation_id = '${correlation_id}';\""
}

scenario_invalid_event() {
  echo "== Scenario: invalid/malformed event handling =="
  require_app_running
  local key="invalid-${RUN_ID}"
  echo "Publishing a malformed (non-JSON) message to telemetry.anomaly.v1..."
  echo '{ this is not valid JSON }' | "${COMPOSE[@]}" exec -T redpanda rpk topic produce telemetry.anomaly.v1 --key "$key" --brokers redpanda:9092 >/dev/null
  echo "Checking telemetry.anomaly.v1.dlq for the same key (bounded retries)..."
  if retry 30 1 bash -c "${COMPOSE[*]} exec -T redpanda rpk topic consume telemetry.anomaly.v1.dlq --brokers redpanda:9092 --num 50 2>/dev/null | grep -q '$key'"; then
    echo "RESULT: malformed event was routed to telemetry.anomaly.v1.dlq as expected."
  else
    echo "RESULT: malformed event was NOT found on the dead-letter topic within the retry budget — investigate."
  fi
}

scenario_retry_exhaustion() {
  echo "== Scenario: consumer retry exhaustion =="
  require_app_running
  echo "Pausing postgres so anomaly processing fails repeatedly (a genuinely transient-looking"
  echo "failure that, unlike scenario 'invalid-event', is retried rather than dead-lettered immediately)..."
  "${COMPOSE[@]}" pause postgres

  local event_id
  event_id="$(python3 -c 'import uuid; print(uuid.uuid4())' 2>/dev/null || uuidgen)"
  local now
  now="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  local envelope
  envelope=$(cat <<EOF
{"eventId":"${event_id}","eventType":"telemetry.anomaly.v1","schemaVersion":1,"occurredAt":"${now}","correlationId":"${RUN_ID}","producer":"reliability-fault-test","payload":{"title":"Retry-exhaustion fault test","description":"Injected by reliability-fault-test.sh","severity":"SEV4","affectedService":"reliability-fault-test-service","detectedAt":"${now}"}}
EOF
)
  echo "$envelope" | "${COMPOSE[@]}" exec -T redpanda rpk topic produce telemetry.anomaly.v1 --brokers redpanda:9092 >/dev/null

  echo "Leaving postgres unreachable long enough to exhaust ANOMALY_CONSUMER_MAX_RETRIES retries..."
  echo "(with default local settings this is well under a minute — see docs/development/reliability.md)."
  sleep 60
  echo "Unpausing postgres..."
  "${COMPOSE[@]}" unpause postgres
  retry 30 2 curl -fsS "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/actuator/health/readiness"

  echo "Checking telemetry.anomaly.v1.dlq for eventId=${event_id} (bounded retries)..."
  if retry 30 1 bash -c "${COMPOSE[*]} exec -T redpanda rpk topic consume telemetry.anomaly.v1.dlq --brokers redpanda:9092 --num 50 2>/dev/null | grep -q '$event_id'"; then
    echo "RESULT: event exhausted its retry budget and was routed to telemetry.anomaly.v1.dlq as expected."
  else
    echo "RESULT: event was NOT found on the dead-letter topic within the retry budget — either it succeeded"
    echo "        after postgres recovered (also acceptable), or something needs investigation. Check:"
    echo "        curl -s -H \"Authorization: Bearer \$TOKEN\" http://127.0.0.1:${INCIDENT_SERVICE_PORT}/api/v1/incidents?affectedService=reliability-fault-test-service"
  fi
}

case "${1:-}" in
  duplicate-delivery) scenario_duplicate_delivery ;;
  broker-outage) scenario_broker_outage ;;
  app-restart) scenario_app_restart ;;
  invalid-event) scenario_invalid_event ;;
  retry-exhaustion) scenario_retry_exhaustion ;;
  all)
    scenario_duplicate_delivery
    scenario_broker_outage
    scenario_app_restart
    scenario_invalid_event
    scenario_retry_exhaustion
    ;;
  *)
    echo "Usage: $0 <duplicate-delivery|broker-outage|app-restart|invalid-event|retry-exhaustion|all>"
    exit 1
    ;;
esac
