#!/usr/bin/env bash
# Shared safety library for SentinelOps Phase 15 chaos experiments. Sourced by
# chaos-experiment.sh — never invoked directly. Every safety rule here is enforced in code, not
# merely documented in chaos-experiments.yaml, so a catalog typo can never silently disable a
# guardrail.

# Refuses to act on any container that isn't explicitly labeled as a chaos-target (see
# infrastructure/docker/docker-compose.yml). This is the literal mechanism behind "restrict
# experiments to explicitly labeled development resources."
chaos_require_labeled_target() {
  local container="$1"
  local label
  label="$(docker inspect --format '{{ index .Config.Labels "sentinelops.chaos-target" }}' "$container" 2>/dev/null || true)"
  if [ "$label" != "true" ]; then
    echo "ABORT: '$container' is not labeled sentinelops.chaos-target=true — refusing to run a chaos experiment against it." >&2
    exit 1
  fi
}

# Requires explicit opt-in via the environment (never on by default, even under the "app"
# profile) — a second, independent gate beyond the container label.
chaos_require_opt_in() {
  if [ "${CHAOS_EXPERIMENTS_ENABLED:-false}" != "true" ]; then
    echo "ABORT: set CHAOS_EXPERIMENTS_ENABLED=true (in .env or the environment) to run chaos experiments." >&2
    echo "This is a deliberate second gate, independent of the chaos-target container label." >&2
    exit 1
  fi
}

# Wraps a chaos action with a hard wall-clock ceiling — no experiment may run unbounded even if
# its own internal loop logic has a bug.
chaos_bounded() {
  local max_seconds="$1"
  shift
  timeout "${max_seconds}s" "$@"
}

CHAOS_LOG_FILE="${CHAOS_LOG_FILE:-/tmp/sentinelops-chaos-$(date +%s)-$$.log}"

chaos_log() {
  local line
  line="[$(date -u +%Y-%m-%dT%H:%M:%SZ)] $*"
  echo "$line" | tee -a "$CHAOS_LOG_FILE"
}

# Registers a recovery function to run unconditionally on exit (success, failure, or Ctrl-C) —
# the same trap-based safety pattern established by Phase 6's reliability-fault-test.sh. Every
# experiment function must call this before doing anything disruptive.
chaos_register_recovery() {
  # shellcheck disable=SC2064
  trap "$1" EXIT INT TERM
}

# Polls a health URL until it reports UP or the timeout elapses; returns non-zero (never hangs)
# on timeout, so callers can implement their own abort-condition messaging.
chaos_wait_healthy() {
  local url="$1" timeout_seconds="${2:-30}" waited=0
  while [ "$waited" -lt "$timeout_seconds" ]; do
    if curl -fsS "$url" 2>/dev/null | grep -q '"status":"UP"'; then
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}
