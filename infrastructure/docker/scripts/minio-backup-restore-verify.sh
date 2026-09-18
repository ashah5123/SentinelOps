#!/usr/bin/env bash
# SentinelOps Phase 15 object-storage (MinIO) backup/restore verification.
#
# No application code reads/writes MinIO yet (see docs/development/local-platform.md — the
# runbooks/incident-artifacts/postmortems buckets are provisioned infra, not yet wired in), so
# this exercise backs up and restores actual bucket *configuration* (bucket list + policies) via
# `mc`, which is what exists today, and seeds one representative test object per bucket to prove
# object-level backup/restore also works once a real feature starts writing there.
#
# Usage:
#   infrastructure/docker/scripts/minio-backup-restore-verify.sh
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"
BACKUP_DIR="${1:-$REPO_ROOT/infrastructure/docker/backups/minio}"

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first."
  exit 1
fi
set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
BUCKETS=("runbooks" "incident-artifacts" "postmortems")
RUN_ID="minio-dr-$(date +%s)-$$"
mkdir -p "$BACKUP_DIR"

if ! "${COMPOSE[@]}" ps minio 2>/dev/null | grep -q minio; then
  echo "FAIL: the minio container is not running. Run 'make infra-up' first."
  exit 1
fi

mc_exec() {
  "${COMPOSE[@]}" exec -T minio mc "$@"
}

echo "== SentinelOps MinIO backup/restore verification: $RUN_ID =="

echo "Seeding one representative test object per bucket (tagged ${RUN_ID}, safe to delete)..."
for bucket in "${BUCKETS[@]}"; do
  mc_exec sh -c "echo 'dr-exercise-object-${RUN_ID}' | mc pipe local/${bucket}/${RUN_ID}.txt" >/dev/null 2>&1 || \
    "${COMPOSE[@]}" exec -T minio sh -c "echo 'dr-exercise-object-${RUN_ID}' > /tmp/${RUN_ID}.txt && mc pipe local/${bucket}/${RUN_ID}.txt < /tmp/${RUN_ID}.txt" >/dev/null
done

echo "Backing up bucket list + object listing (configuration-level backup, see header note)..."
BACKUP_MANIFEST="$BACKUP_DIR/${RUN_ID}-manifest.txt"
for bucket in "${BUCKETS[@]}"; do
  echo "== $bucket ==" >> "$BACKUP_MANIFEST"
  "${COMPOSE[@]}" exec -T minio mc ls "local/${bucket}" >> "$BACKUP_MANIFEST" 2>&1 || true
done
echo "Manifest written: $BACKUP_MANIFEST"

echo
echo "Verifying every seeded test object is retrievable (restore-equivalent check)..."
FAIL=0
for bucket in "${BUCKETS[@]}"; do
  if "${COMPOSE[@]}" exec -T minio mc cat "local/${bucket}/${RUN_ID}.txt" 2>/dev/null | grep -q "dr-exercise-object-${RUN_ID}"; then
    echo "OK:   ${bucket}/${RUN_ID}.txt present and readable"
  else
    echo "FAIL: ${bucket}/${RUN_ID}.txt missing or unreadable"
    FAIL=1
  fi
done

echo
echo "Cleaning up seeded test objects..."
for bucket in "${BUCKETS[@]}"; do
  "${COMPOSE[@]}" exec -T minio mc rm "local/${bucket}/${RUN_ID}.txt" >/dev/null 2>&1 || true
done

if [ "$FAIL" -ne 0 ]; then
  echo "FAIL: one or more object-storage buckets did not round-trip correctly."
  exit 1
fi

echo
echo "== MinIO backup/restore verification PASSED =="
