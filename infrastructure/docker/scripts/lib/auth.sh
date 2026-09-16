#!/usr/bin/env bash
# Shared helper for local scripts that call incident-service's authenticated API (Phase 7).
# Obtains a real access token from the local Keycloak realm via the Resource Owner Password
# Credentials grant against the "sentinelops-api" client and one of the synthetic demo users
# defined in infrastructure/docker/keycloak/realm-export.json — the same local/demo-only
# workflow documented in docs/development/security.md. Never echoes the token itself.
#
# Usage: source this file, then call:
#   TOKEN="$(fetch_incident_service_token responder-demo)"
#   curl -H "Authorization: Bearer ${TOKEN}" ...

fetch_incident_service_token() {
  local demo_user="$1" # viewer-demo | responder-demo | admin-demo
  local keycloak_port="${KEYCLOAK_PORT:-8180}"
  local password="${demo_user}-local-only"

  curl -fsS -X POST \
    "http://127.0.0.1:${keycloak_port}/realms/sentinelops/protocol/openid-connect/token" \
    -d grant_type=password \
    -d client_id=sentinelops-api \
    -d "username=${demo_user}" \
    -d "password=${password}" \
    | python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])'
}
