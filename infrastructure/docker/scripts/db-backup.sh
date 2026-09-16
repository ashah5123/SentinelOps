#!/usr/bin/env bash
# SentinelOps PostgreSQL backup (Phase 9).
#
# Produces a compressed, timestamped pg_dump custom-format backup of the whole database (every
# schema: incidents, audit, telemetry, runbooks) via the running "postgres" Compose container.
# Connection settings come only from .env — no credential is ever hardcoded here.
#
# Usage:
#   infrastructure/docker/scripts/db-backup.sh [outputDir]
#
# Default outputDir: infrastructure/docker/backups/ (git-ignored — see .gitignore).
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

: "${POSTGRES_USER:?FAIL: POSTGRES_USER is not set in .env}"
: "${POSTGRES_PASSWORD:?FAIL: POSTGRES_PASSWORD is not set in .env}"
: "${POSTGRES_DB:?FAIL: POSTGRES_DB is not set in .env}"

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
OUTPUT_DIR="${1:-$REPO_ROOT/infrastructure/docker/backups}"
mkdir -p "$OUTPUT_DIR"

if ! "${COMPOSE[@]}" ps postgres 2>/dev/null | grep -q postgres; then
  echo "FAIL: the postgres container is not running. Run 'make infra-up' first."
  exit 1
fi

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
BACKUP_FILE="$OUTPUT_DIR/sentinelops-${POSTGRES_DB}-${TIMESTAMP}.dump"

echo "== Backing up database '${POSTGRES_DB}' =="
echo "Output: ${BACKUP_FILE}"

# -Fc: PostgreSQL's own compressed "custom" format (portable, pg_restore-only, supports
# selective/parallel restore) — not `gzip`d plain SQL, which pg_restore cannot consume directly.
"${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc --no-owner --no-privileges \
  > "$BACKUP_FILE"

if [ ! -s "$BACKUP_FILE" ]; then
  echo "FAIL: backup file is empty — pg_dump likely failed. See output above."
  rm -f "$BACKUP_FILE"
  exit 1
fi

SIZE_BYTES="$(wc -c < "$BACKUP_FILE" | tr -d '[:space:]')"
echo "== Backup complete =="
echo "File:  ${BACKUP_FILE}"
echo "Size:  ${SIZE_BYTES} bytes"
echo
echo "NOTE: a successful pg_dump does not by itself prove the backup is restorable — run"
echo "  infrastructure/docker/scripts/db-restore-verify.sh ${BACKUP_FILE}"
echo "before relying on this backup."
