#!/usr/bin/env bash
# Captures the environment a benchmark run was executed in — git revision/dirty state, OS/CPU/
# memory, Docker resource limits, pinned service image versions, and the relevant runtime
# configuration — so a result can be interpreted later and compared fairly against another run.
# Never includes credentials or secrets (only image tags, pool sizes, and hardware facts).
#
# Usage: infrastructure/docker/scripts/benchmark-env-info.sh <outputFile>
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
ENV_FILE="$REPO_ROOT/.env"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
OUT="${1:-/dev/stdout}"

if [ -f "$ENV_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

{
  echo "# SentinelOps benchmark environment capture"
  echo "captured_at_utc: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo
  echo "## Git"
  echo "revision: $(cd "$REPO_ROOT" && git rev-parse HEAD 2>/dev/null || echo unknown)"
  if [ -n "$(cd "$REPO_ROOT" && git status --porcelain 2>/dev/null)" ]; then
    echo "working_tree: DIRTY"
    echo "dirty_files:"
    (cd "$REPO_ROOT" && git status --porcelain 2>/dev/null | sed 's/^/  /')
  else
    echo "working_tree: clean"
  fi
  echo
  echo "## Host"
  echo "os: $(uname -s) $(uname -r) ($(uname -m))"
  if command -v sysctl >/dev/null 2>&1; then
    echo "cpu: $(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown) x $(sysctl -n hw.ncpu 2>/dev/null || echo unknown) logical cores"
    mem_bytes="$(sysctl -n hw.memsize 2>/dev/null || echo 0)"
    echo "memory_gb: $(python3 -c "print(round(${mem_bytes} / 1024**3, 1))" 2>/dev/null || echo unknown)"
  elif [ -f /proc/cpuinfo ]; then
    echo "cpu: $(grep -m1 'model name' /proc/cpuinfo | cut -d: -f2 | sed 's/^ //') x $(nproc) logical cores"
    echo "memory_gb: $(awk '/MemTotal/{printf "%.1f", $2/1024/1024}' /proc/meminfo)"
  fi
  echo
  echo "## Docker"
  if command -v docker >/dev/null 2>&1; then
    echo "docker_version: $(docker --version 2>/dev/null || echo unknown)"
    echo "compose_version: $(docker compose version 2>/dev/null || echo unknown)"
  else
    echo "docker_version: NOT INSTALLED IN THIS ENVIRONMENT"
  fi
  echo
  echo "## Compose resource limits (as configured, not necessarily what the host can honor)"
  python3 - "$COMPOSE_FILE" <<'PYEOF'
import sys
import yaml
with open(sys.argv[1]) as f:
    doc = yaml.safe_load(f)
for name in ("postgres", "redpanda", "keycloak", "incident-service"):
    svc = doc.get("services", {}).get(name, {})
    limits = svc.get("deploy", {}).get("resources", {}).get("limits", {})
    image = svc.get("image", svc.get("build", {}).get("context", "unknown"))
    print(f"{name}: image={image} cpus={limits.get('cpus', '?')} memory={limits.get('memory', '?')}")
PYEOF
  echo
  echo "## Relevant application configuration (from .env, non-secret values only)"
  echo "DB_POOL_MAX_SIZE=${DB_POOL_MAX_SIZE:-10 (default)}"
  echo "DB_POOL_MIN_IDLE=${DB_POOL_MIN_IDLE:-2 (default)}"
  echo "OUTBOX_POLLING_INTERVAL=${OUTBOX_POLLING_INTERVAL:-2s (default)}"
  echo "OUTBOX_BATCH_SIZE=${OUTBOX_BATCH_SIZE:-50 (default)}"
  echo "OUTBOX_LEASE_DURATION=${OUTBOX_LEASE_DURATION:-30s (default)}"
  echo "OUTBOX_PUBLISH_TIMEOUT=${OUTBOX_PUBLISH_TIMEOUT:-10s (default)}"
  echo "REDPANDA_TOPIC_PARTITIONS=${REDPANDA_TOPIC_PARTITIONS:-3 (default)}"
} > "$OUT"

echo "Environment info written to: $OUT" >&2
