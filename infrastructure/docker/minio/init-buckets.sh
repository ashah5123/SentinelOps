#!/bin/sh
# One-time, idempotent MinIO bucket initialization for SentinelOps.
# Creates each required bucket only if it does not already exist, and
# ensures none of them are publicly accessible.
set -eu

: "${MINIO_ROOT_USER:?MINIO_ROOT_USER must be set}"
: "${MINIO_ROOT_PASSWORD:?MINIO_ROOT_PASSWORD must be set}"

# MC_HOST_local configures the "local" alias via environment variable so no
# credential is ever written to a config file or passed as a CLI argument.
export MC_HOST_local="http://${MINIO_ROOT_USER}:${MINIO_ROOT_PASSWORD}@minio:9000"

BUCKETS="runbooks incident-artifacts postmortems"

echo "waiting for minio API..."
attempt=0
until mc ls local >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 15 ]; then
    echo "ERROR: minio did not become reachable in time" >&2
    exit 1
  fi
  sleep 2
done

for bucket in $BUCKETS; do
  if mc ls "local/$bucket" >/dev/null 2>&1; then
    echo "bucket already exists: $bucket"
  else
    echo "creating bucket: $bucket"
    mc mb "local/$bucket"
  fi
  # Ensure the bucket carries no anonymous/public access policy.
  mc anonymous set none "local/$bucket"
done

echo "SentinelOps: MinIO bucket initialization complete."
