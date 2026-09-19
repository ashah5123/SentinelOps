#!/usr/bin/env bash
# Safely tears down the SentinelOps demo environment: stops every Compose profile this project
# defines and removes their volumes — never touches the source tree, git history, or anything
# outside Docker Compose's own managed resources.
#
# Usage:
#   infrastructure/docker/scripts/full-demo-cleanup.sh
#   make demo-cleanup
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "No .env found — nothing to tear down (the demo was never started with this .env)."
  exit 0
fi

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

echo "Stopping every SentinelOps Compose profile and removing volumes..."
"${COMPOSE[@]}" --profile app --profile observability --profile console --profile benchmark down --volumes

echo "Done. Persistent data (Postgres/Redis/Redpanda/MinIO/Grafana volumes) has been removed."
echo "Local backup files under infrastructure/docker/backups/ and validation results under"
echo "infrastructure/docker/results/ are left in place (git-ignored, not part of the demo state)."
