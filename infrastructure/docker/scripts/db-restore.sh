#!/usr/bin/env bash
# SentinelOps PostgreSQL restore (Phase 9).
#
# Restores a pg_dump custom-format backup (see db-backup.sh) into an EXPLICITLY named target
# database — never the primary POSTGRES_DB by default, so an accidental invocation cannot
# silently clobber real development data. The target database must already exist (create it first
# with `make db-create-database NAME=...` or `CREATE DATABASE`, or use
# db-restore-verify.sh, which creates and drops its own isolated database for you).
#
# Usage:
#   infrastructure/docker/scripts/db-restore.sh <backupFile> <targetDatabaseName> [--yes]
#
# Refuses to run without both arguments. Asks for interactive confirmation before restoring
# unless --yes is passed (used by non-interactive callers like db-restore-verify.sh, which
# already restores into a database it just created for exactly this purpose).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first."
  exit 1
fi
if [ $# -lt 2 ]; then
  echo "Usage: $0 <backupFile> <targetDatabaseName> [--yes]"
  echo "Refusing to guess a target database — it must always be named explicitly."
  exit 1
fi

BACKUP_FILE="$1"
TARGET_DB="$2"
ASSUME_YES="${3:-}"

if [ ! -f "$BACKUP_FILE" ]; then
  echo "FAIL: backup file not found: $BACKUP_FILE"
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${POSTGRES_USER:?FAIL: POSTGRES_USER is not set in .env}"
: "${POSTGRES_PASSWORD:?FAIL: POSTGRES_PASSWORD is not set in .env}"
: "${POSTGRES_DB:?FAIL: POSTGRES_DB is not set in .env}"

if [ "$TARGET_DB" = "$POSTGRES_DB" ]; then
  echo "FAIL: refusing to restore over POSTGRES_DB ('${POSTGRES_DB}') directly — restore into a"
  echo "      differently named database (e.g. '${POSTGRES_DB}_restore') and swap application"
  echo "      traffic over deliberately, or use db-restore-verify.sh for a disposable check."
  exit 1
fi

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")

if ! "${COMPOSE[@]}" ps postgres 2>/dev/null | grep -q postgres; then
  echo "FAIL: the postgres container is not running. Run 'make infra-up' first."
  exit 1
fi

DB_EXISTS="$("${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  psql -U "$POSTGRES_USER" -d postgres -tAc \
  "SELECT 1 FROM pg_database WHERE datname = '${TARGET_DB}';" 2>/dev/null | tr -d '[:space:]')"

if [ "$DB_EXISTS" != "1" ]; then
  echo "FAIL: target database '${TARGET_DB}' does not exist. Create it first, e.g.:"
  echo "  docker compose --env-file .env -f infrastructure/docker/docker-compose.yml exec postgres \\"
  echo "    psql -U \"\$POSTGRES_USER\" -d postgres -c 'CREATE DATABASE ${TARGET_DB};'"
  exit 1
fi

echo "== Restoring $(basename "$BACKUP_FILE") into '${TARGET_DB}' =="
echo "WARNING: this replaces every object pg_restore --clean can drop in '${TARGET_DB}' with the"
echo "         contents of the backup. This does NOT touch '${POSTGRES_DB}' or any other database."

if [ "$ASSUME_YES" != "--yes" ]; then
  printf "Type 'yes' to continue: "
  read -r confirm
  if [ "$confirm" != "yes" ]; then
    echo "Aborted. Nothing was changed."
    exit 0
  fi
fi

# --clean --if-exists: drop existing objects in the target database before recreating them, so a
# restore into a non-empty target still ends up matching the backup exactly, without erroring on
# objects that don't exist yet. --no-owner/--no-privileges: this container's bootstrap role may
# not match whatever role originally owned the dumped objects.
cat "$BACKUP_FILE" | "${COMPOSE[@]}" exec -T -e PGPASSWORD="$POSTGRES_PASSWORD" postgres \
  pg_restore -U "$POSTGRES_USER" -d "$TARGET_DB" --clean --if-exists --no-owner --no-privileges

echo "== Restore complete: ${TARGET_DB} =="
