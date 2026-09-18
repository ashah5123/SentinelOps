#!/usr/bin/env bash
# SentinelOps Phase 8 benchmark orchestrator.
#
# Runs one k6 scenario (infrastructure/docker/k6/scenarios/<scenario>.js) via the "benchmark"
# Compose profile's k6 service, while sampling incident-service's own /actuator/prometheus
# (outbox backlog/oldest-pending-age/dead-letter/retry counters, HikariCP pool usage, JVM/CPU) at
# a fixed interval before, during, and for a configurable window after the run — so asynchronous
# outbox/consumer behavior and resource usage are captured alongside k6's own HTTP-level numbers,
# without conflating the two. Every run is captured with its git revision/dirty state, host, and
# configuration (see benchmark-env-info.sh) so results can be interpreted and compared later.
#
# Usage:
#   infrastructure/docker/scripts/benchmark-run.sh <scenario> [extra k6 -e VAR=value ...]
#
# Scenarios (see infrastructure/docker/k6/scenarios/): smoke | paginated-reads |
#   incident-creation | lifecycle-transitions | mixed-workload | burst-recovery |
#   unauthorized-access | sustained-telemetry-ingestion | ai-triage-saturation |
#   concurrent-remediation-requests | notification-fanout | console-polling
#
# Phase 15 addition: to measure "recovery after dependency restoration," run a sustained
# scenario (e.g. mixed-workload or sustained-telemetry-ingestion) concurrently with
# infrastructure/docker/scripts/chaos-experiment.sh (e.g. kafka-broker-fault or postgres-fault)
# in a second terminal — this script's own RECOVERY_WINDOW_SECONDS post-run sampling then
# captures how quickly backlog/latency metrics return to baseline once the chaos experiment's
# own recovery step (which every experiment runs unconditionally) restores the dependency.
#
# Env vars this script itself reads (all optional):
#   BENCHMARK_RUN_ID          run identifier (default: generated); pass the same value used by
#                             benchmark-seed.sh to read its seeded dataset
#   METRICS_SAMPLE_INTERVAL   seconds between metric samples (default 5)
#   RECOVERY_WINDOW_SECONDS   how long to keep sampling metrics after k6 exits (default 0, or 60
#                             for burst-recovery — see the case switch below)
#
# Requires the "app" Compose profile already running (make incident-up).
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="$REPO_ROOT/infrastructure/docker/docker-compose.yml"
ENV_FILE="$REPO_ROOT/.env"

if [ ! -f "$ENV_FILE" ]; then
  echo "FAIL: $ENV_FILE not found. Copy .env.example to .env first."
  exit 1
fi
if [ $# -lt 1 ]; then
  echo "Usage: $0 <scenario> [-e VAR=value ...]"
  exit 1
fi

SCENARIO="$1"
shift
EXTRA_K6_ARGS=("$@")

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

COMPOSE=(docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE")
INCIDENT_SERVICE_PORT="${INCIDENT_SERVICE_PORT:-8081}"
METRICS_USER="${ACTUATOR_METRICS_USERNAME:-metrics}"
METRICS_PASSWORD="${ACTUATOR_METRICS_PASSWORD:-change-me-local-dev-only}"
METRICS_SAMPLE_INTERVAL="${METRICS_SAMPLE_INTERVAL:-5}"

RUN_ID="${BENCHMARK_RUN_ID:-$(date +%s)}"
DEFAULT_RECOVERY_WINDOW=0
[ "$SCENARIO" = "burst-recovery" ] && DEFAULT_RECOVERY_WINDOW=60
RECOVERY_WINDOW_SECONDS="${RECOVERY_WINDOW_SECONDS:-$DEFAULT_RECOVERY_WINDOW}"

TIMESTAMP="$(date -u +%Y%m%dT%H%M%SZ)"
RESULTS_DIR="$REPO_ROOT/infrastructure/docker/k6/results/${RUN_ID}/${SCENARIO}-${TIMESTAMP}"
mkdir -p "$RESULTS_DIR"

echo "== SentinelOps benchmark: scenario=${SCENARIO} runId=${RUN_ID} =="
echo "Results directory: $RESULTS_DIR"

"$REPO_ROOT/infrastructure/docker/scripts/benchmark-env-info.sh" "$RESULTS_DIR/environment.md"

if ! "${COMPOSE[@]}" ps incident-service 2>/dev/null | grep -q incident-service; then
  echo "FAIL: incident-service is not running. Run 'make incident-up' first."
  exit 1
fi

METRICS_CSV="$RESULTS_DIR/metrics-samples.csv"
echo "timestamp_utc,outbox_backlog,outbox_oldest_pending_age_seconds,outbox_dead_lettered_total,consumer_dead_lettered_total,consumer_retry_attempts_total,hikaricp_active,hikaricp_pending,process_cpu_usage,jvm_heap_used_bytes,auth_failures_total,authz_denials_total,audit_persistence_failures_total" \
  > "$METRICS_CSV"

sample_metrics() {
  local raw
  raw="$(curl -fsS -u "${METRICS_USER}:${METRICS_PASSWORD}" \
    "http://127.0.0.1:${INCIDENT_SERVICE_PORT}/actuator/prometheus" 2>/dev/null)"
  if [ -z "$raw" ]; then
    return
  fi
  python3 - "$raw" <<'PYEOF' >> "$METRICS_CSV"
import sys, datetime

text = sys.argv[1]

def metric_value(metric_name, label_substr=None):
    """Sums every matching Prometheus text-format line (correct for both a single-valued gauge
    and a labeled counter/gauge family, e.g. summing JVM heap across memory pools)."""
    total = 0.0
    found = False
    for line in text.splitlines():
        if line.startswith("#"):
            continue
        if not (line.startswith(metric_name + " ") or line.startswith(metric_name + "{")):
            continue
        if label_substr and label_substr not in line:
            continue
        try:
            total += float(line.rsplit(" ", 1)[1])
            found = True
        except (IndexError, ValueError):
            continue
    return total if found else ""

row = [
    datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    metric_value("sentinelops_outbox_backlog"),
    metric_value("sentinelops_outbox_oldest_pending_age_seconds"),
    metric_value("sentinelops_outbox_dead_lettered_total"),
    metric_value("sentinelops_consumer_dead_lettered_total"),
    metric_value("sentinelops_consumer_retry_attempts_total"),
    metric_value("hikaricp_connections_active"),
    metric_value("hikaricp_connections_pending"),
    metric_value("process_cpu_usage"),
    metric_value("jvm_memory_used_bytes", label_substr='area="heap"'),
    metric_value("sentinelops_auth_authentication_failures_total"),
    metric_value("sentinelops_auth_authorization_denials_total"),
    metric_value("sentinelops_audit_persistence_failures_total"),
]
print(",".join(str(v) for v in row))
PYEOF
}

sample_metrics # baseline, before load starts

SAMPLER_PID=""
start_sampler() {
  ( while true; do sleep "$METRICS_SAMPLE_INTERVAL"; sample_metrics; done ) &
  SAMPLER_PID=$!
}
stop_sampler() {
  if [ -n "$SAMPLER_PID" ]; then
    kill "$SAMPLER_PID" >/dev/null 2>&1 || true
    wait "$SAMPLER_PID" 2>/dev/null || true
  fi
}
trap stop_sampler EXIT

start_sampler

echo "Running k6 scenario '${SCENARIO}'..."
K6_SUMMARY="$RESULTS_DIR/k6-summary.json"
K6_EXIT=0
"${COMPOSE[@]}" --profile benchmark run --rm \
  -e BENCHMARK_RUN_ID="${RUN_ID}" \
  "${EXTRA_K6_ARGS[@]}" \
  k6 run "/scripts/scenarios/${SCENARIO}.js" \
  --summary-trend-stats "avg,min,med,p(90),p(95),p(99),max" \
  --summary-export "/results/$(basename "$RESULTS_DIR")-k6-summary.json" \
  || K6_EXIT=$?

# The k6 container writes into the mounted results volume under its own path; move/rename to the
# per-run directory k6-summary.json name for a predictable path regardless of scenario/timestamp.
if [ -f "$REPO_ROOT/infrastructure/docker/k6/results/$(basename "$RESULTS_DIR")-k6-summary.json" ]; then
  mv "$REPO_ROOT/infrastructure/docker/k6/results/$(basename "$RESULTS_DIR")-k6-summary.json" "$K6_SUMMARY"
fi

if [ "$RECOVERY_WINDOW_SECONDS" -gt 0 ]; then
  echo "Sampling recovery for ${RECOVERY_WINDOW_SECONDS}s after the run ended..."
  sleep "$RECOVERY_WINDOW_SECONDS"
  sample_metrics
fi

stop_sampler
trap - EXIT

echo "== Run complete (k6 exit code: ${K6_EXIT}) =="
echo "Raw k6 summary:    ${K6_SUMMARY}"
echo "Metric samples:    ${METRICS_CSV}"
echo "Environment info:  ${RESULTS_DIR}/environment.md"

if [ -f "$K6_SUMMARY" ]; then
  python3 - "$K6_SUMMARY" "$METRICS_CSV" "$RESULTS_DIR/summary.md" "$SCENARIO" "$RUN_ID" <<'PYEOF'
import sys, json, csv

summary_path, metrics_csv, out_path, scenario, run_id = sys.argv[1:6]

with open(summary_path) as f:
    summary = json.load(f)

metrics = summary.get("metrics", {})

def fmt(metric_name, stat, unit=""):
    m = metrics.get(metric_name)
    if not m:
        return "n/a"
    v = m.get(stat)
    if v is None:
        return "n/a"
    return f"{v:.2f}{unit}"

rows = []
try:
    with open(metrics_csv) as f:
        rows = list(csv.DictReader(f))
except FileNotFoundError:
    pass

lines = []
lines.append(f"# Benchmark summary: {scenario} (run {run_id})\n")
lines.append("## HTTP-level results (k6)\n")
lines.append(f"- Requests: {fmt('http_reqs', 'count')}")
lines.append(f"- Failed request rate: {fmt('http_req_failed', 'value')}")
lines.append(f"- Duration p50: {fmt('http_req_duration', 'med', 'ms')}")
lines.append(f"- Duration p95: {fmt('http_req_duration', 'p(95)', 'ms')}")
lines.append(f"- Duration p99: {fmt('http_req_duration', 'p(99)', 'ms')}")
lines.append(f"- Iterations: {fmt('iterations', 'count')}")
lines.append(f"- Dropped iterations (saturation): {fmt('dropped_iterations', 'count')}")
lines.append("")
lines.append("## Async/resource metrics sampled from /actuator/prometheus\n")
if rows:
    first, last = rows[0], rows[-1]
    for key, label in [
        ("outbox_backlog", "Outbox backlog"),
        ("outbox_oldest_pending_age_seconds", "Outbox oldest pending age (s)"),
        ("outbox_dead_lettered_total", "Outbox dead-lettered (total)"),
        ("consumer_dead_lettered_total", "Consumer dead-lettered (total)"),
        ("consumer_retry_attempts_total", "Consumer retry attempts (total)"),
        ("hikaricp_active", "HikariCP active connections"),
        ("hikaricp_pending", "HikariCP pending (threads waiting for a connection)"),
        ("process_cpu_usage", "Process CPU usage (0-1)"),
        ("auth_failures_total", "Authentication failures (total)"),
        ("authz_denials_total", "Authorization denials (total)"),
        ("audit_persistence_failures_total", "Audit persistence failures (total)"),
    ]:
        lines.append(f"- {label}: before={first.get(key, 'n/a')} -> after={last.get(key, 'n/a')}")
else:
    lines.append("(no metric samples recorded — was incident-service reachable?)")
lines.append("")
lines.append(f"Raw k6 summary: {summary_path}")
lines.append(f"Raw metric samples: {metrics_csv}")

with open(out_path, "w") as f:
    f.write("\n".join(lines) + "\n")
print(f"Readable summary written to: {out_path}")
PYEOF
fi

exit "$K6_EXIT"
