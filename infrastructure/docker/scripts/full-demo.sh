#!/usr/bin/env bash
# SentinelOps portfolio demonstration (Phase 16).
#
# A single command that starts the full local platform, seeds deterministic data, and walks
# through the platform's complete workflow end to end — reusing every phase's own demo/validation
# script rather than reimplementing them, so this is always exercising the real, tested code
# paths, not a separate demo-only shortcut.
#
# Usage:
#   infrastructure/docker/scripts/full-demo.sh
#   make demo
#
# Clean up afterward with: infrastructure/docker/scripts/full-demo-cleanup.sh (or `make demo-cleanup`).
#
# Demonstrates, in order:
#   1. Telemetry and alert ingestion            (alert-demo.sh, steps 1/6)
#   2. Alert deduplication and correlation      (alert-demo.sh, steps 2/3/4)
#   3. Incident creation                        (alert-demo.sh, step 1's result)
#   4. AI-assisted triage with supporting evidence (POST .../ai-suggestions)
#   5. Operator review in the console           (printed URL — manual step, see below)
#   6. MCP-based proposal                       (mcp-diagnostic-client.py's propose_action demo)
#   7. Policy evaluation and approval           (remediation-demo.sh's medium-risk runbook)
#   8. Controlled remediation execution         (remediation-demo.sh)
#   9. Post-action health validation            (remediation-demo.sh's health-check step)
#  10. Audit logging and observability          (printed URLs: Grafana, audit API)
#  11. Failure injection, rollback, and recovery (remediation-demo.sh's fault-injection scenario)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"
SCRIPTS_DIR="$REPO_ROOT/infrastructure/docker/scripts"

if [ ! -f "$ENV_FILE" ]; then
  echo "No .env found — copying .env.example (local-dev-only placeholder values)."
  cp "$REPO_ROOT/.env.example" "$ENV_FILE"
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a
COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
BASE_URL="http://localhost:${INCIDENT_SERVICE_PORT:-8081}"
GRAFANA_PORT="${GRAFANA_PORT:-3000}"
MAILPIT_UI_PORT="${MAILPIT_UI_PORT:-8025}"

step() { echo; echo "=================================================================="; echo "STEP: $1"; echo "=================================================================="; }

step "0/11 — starting the full local platform (app + observability profiles)"
"${COMPOSE[@]}" up -d --wait postgres redis redpanda minio
"${COMPOSE[@]}" up --exit-code-from redpanda-topics-init redpanda-topics-init
"${COMPOSE[@]}" up --exit-code-from minio-bucket-init minio-bucket-init
"${COMPOSE[@]}" --profile app up -d --wait keycloak incident-service telemetry-correlation-service mailpit webhook-sink
"${COMPOSE[@]}" --profile observability up -d --wait prometheus grafana loki tempo alertmanager otel-collector

# shellcheck disable=SC1091
source "$SCRIPTS_DIR/lib/auth.sh"

step "1-3/11 — telemetry/alert ingestion, deduplication, correlation, incident creation"
bash "$SCRIPTS_DIR/alert-demo.sh" "$BASE_URL"

step "4/11 — AI-assisted triage with supporting evidence"
RESPONDER_TOKEN="$(fetch_incident_service_token responder-demo)"
DEMO_INCIDENT_ID="$(curl -fsS "$BASE_URL/api/v1/incidents?size=1&sort=detectedAt,desc" \
  -H "Authorization: Bearer $RESPONDER_TOKEN" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d["content"][0]["id"])')"
echo "Requesting AI triage for the most recently created incident ($DEMO_INCIDENT_ID)..."
curl -fsS -X POST "$BASE_URL/api/v1/incidents/$DEMO_INCIDENT_ID/ai-suggestions" \
  -H "Authorization: Bearer $RESPONDER_TOKEN" | python3 -m json.tool

step "5/11 — operator review in the console (manual step)"
echo "Open the console (see frontend/README.md for 'npm run dev') and sign in as responder-demo"
echo "to review incident $DEMO_INCIDENT_ID, its AI suggestion, and its evidence timeline."

step "6/11 — MCP-based proposal"
if [ -f "$REPO_ROOT/services/incident-service/target/incident-service.jar" ]; then
  MCP_STDIO_ACCESS_TOKEN="$RESPONDER_TOKEN" python3 "$SCRIPTS_DIR/mcp-diagnostic-client.py" \
    "$REPO_ROOT/services/incident-service/target/incident-service.jar" "$DEMO_INCIDENT_ID"
else
  echo "Skipping (run 'mvn package' in services/incident-service first — see docs/development/mcp-server.md)."
fi
echo "Note: MCP's propose_action tool proposes incident-level actions (acknowledge/assign/"
echo "escalate/etc.) — the policy-evaluated remediation-runbook flow below (steps 7-9) is"
echo "invoked via the same REST endpoint an MCP remediation tool would call; this repository"
echo "does not currently expose remediation-runbook proposals as an MCP tool (see"
echo "docs/development/mcp-server.md's known limitations)."

step "7-9/11 — policy evaluation, approval, controlled remediation execution, health validation"
bash "$SCRIPTS_DIR/remediation-demo.sh" "$BASE_URL"

step "10/11 — audit logging and observability"
echo "Audit trail: GET $BASE_URL/api/v1/audit (bearer token required)"
echo "Grafana:     http://localhost:${GRAFANA_PORT} (see infrastructure/docker/observability/grafana/dashboards/)"
echo "Mailpit:     http://localhost:${MAILPIT_UI_PORT} (notification emails sent during this demo)"

step "11/11 — failure injection, rollback, and recovery"
echo "Already exercised by remediation-demo.sh above (its fault-injection scenario forces a"
echo "target unhealthy and shows the automatic rollback). To see infrastructure-level failure"
echo "injection and recovery separately, run:"
echo "  CHAOS_EXPERIMENTS_ENABLED=true infrastructure/docker/scripts/chaos-experiment.sh kafka-broker-fault"

echo
echo "== Demo complete. Clean up with: infrastructure/docker/scripts/full-demo-cleanup.sh =="
