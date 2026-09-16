# SentinelOps Performance Testing and Reproducible Benchmarks (Phase 8)

Status: Phase 8. Describes how to run reproducible, local, free-and-open-source load benchmarks
against the incident service, using only the existing Docker Compose environment, k6
(via its official Docker image — nothing to install locally), and the real Keycloak-backed
authentication added in Phase 7. See `docs/benchmarks/phase-8-performance.md` for this
environment's actual (unexecuted, Docker-blocked) run and why no optimization was attempted.

## What this measures, and what it deliberately does not

This benchmarks the incident service's own request-handling and asynchronous outbox/consumer
behavior under load, on a single developer laptop. It is **not** a production capacity guarantee,
does not model multi-region latency, and does not exercise the telemetry-correlation service
(out of scope, same as Phase 7's security work — see that phase's own security notice).

## Prerequisites

- The existing Docker Compose environment (`app` profile) — no new local installs. k6 itself runs
  as a one-shot container (`grafana/k6`, pinned) via the new `benchmark` Compose profile; you never
  install k6 on your machine.
- `.env` copied from `.env.example` (see `docs/development/security.md` for the Keycloak setup
  this reuses unchanged).

```bash
cp .env.example .env   # if not already done
make incident-up        # Postgres, Redpanda, Keycloak, incident-service
```

## Authentication for benchmark traffic

Every scenario authenticates exactly like a real client: it fetches a real access token from the
local Keycloak realm for one of the three synthetic demo users (`viewer-demo`, `responder-demo`,
`admin-demo` — see `infrastructure/docker/keycloak/realm-export.json` and
`docs/development/security.md`), via the shared helper `infrastructure/docker/k6/lib/auth.js`
(k6 scenarios) or `infrastructure/docker/scripts/lib/auth.sh` (shell scripts). No benchmark script
bypasses authentication, disables audit logging, or hardcodes a token — tokens are always fetched
fresh from Keycloak at run time and refreshed automatically before they expire.

## Seeding deterministic benchmark data

```bash
make benchmark-seed RUN_ID=my-run-1 SIZE=200 SEED=42
```

This creates `SIZE` incidents via the real, authenticated `POST /api/v1/incidents` API (not
direct database writes), all sharing `affectedService = "bench-my-run-1"`. The severity for each
incident is drawn from a `random.seed(42)`-seeded Python sequence, so the same `SEED` always
produces the same distribution — useful for comparing two runs fairly. Seeded incident IDs are
saved to `infrastructure/docker/k6/results/my-run-1/seeded-incident-ids.txt`.

Benchmark data is always isolated from real development data by that `affectedService` marker —
nothing in this workflow ever creates or reads incidents without it (except the
`incident-creation`, `lifecycle-transitions`, and `burst-recovery` scenarios, which create their
own fresh, self-owned incidents per iteration and don't need pre-seeded data).

## Scoped cleanup

```bash
make benchmark-cleanup RUN_ID=my-run-1     # delete only this run's data
make benchmark-cleanup ALL=1                # delete every "bench-*" run (asks for confirmation)
```

`infrastructure/docker/scripts/benchmark-cleanup.sh` only ever deletes rows it can trace back to
a `bench-<runId>` `affected_service` value (incidents, their status-history/evidence/audit/outbox
rows) plus idempotency-key records using the benchmark scripts' own key prefixes. It never runs an
unscoped `DELETE`, and always asks for interactive confirmation first.

## Running scenarios

All scenarios live in `infrastructure/docker/k6/scenarios/` and are launched via
`infrastructure/docker/scripts/benchmark-run.sh <scenario>` (or the matching `make benchmark-*`
target), which also captures the environment (git revision/dirty state, host CPU/memory, Docker
resource limits, pinned image versions, and relevant `.env` configuration — see
`benchmark-env-info.sh`) and samples incident-service's own `/actuator/prometheus` before, during,
and after the run.

| Scenario | Make target | What it exercises |
|---|---|---|
| `smoke` | `make benchmark-smoke` | Bounded functional correctness check (also runs in CI) — not a load test. |
| `unauthorized-access` | `make benchmark-unauthorized` | Intentional 401/403 checks, kept separate from every performance number. |
| `paginated-reads` | `make benchmark-reads RUN_ID=...` | VIEWER browsing the seeded dataset with pagination/filtering. |
| `incident-creation` | `make benchmark-create` | RESPONDER creating incidents; HTTP acceptance latency only (see below for async completion). |
| `lifecycle-transitions` | `make benchmark-lifecycle` | Create + valid DETECTED→INVESTIGATING→MITIGATING→RESOLVED transitions, one incident per iteration (no cross-VU conflicts). |
| `mixed-workload` | `make benchmark-mixed RUN_ID=...` | ~70% reads / ~30% writes, a realistic blend. |
| `burst-recovery` | `make benchmark-burst` | A short (~15s), steep spike, then k6 stops while the orchestrator keeps sampling metrics for a 60s recovery window. |

Configure concurrency/duration with env vars, e.g.:

```bash
BENCHMARK_VUS=20 BENCHMARK_DURATION=2m make benchmark-mixed RUN_ID=my-run-1
```

Workload size defaults are small and safe for a laptop (10-15 VUs, 1-2 minutes) — raise
`BENCHMARK_VUS`/`BENCHMARK_DURATION` deliberately for a heavier run, and watch host CPU/memory
while doing so.

## Establishing a baseline (warm-up + 3 runs)

```bash
BENCHMARK_VUS=15 BENCHMARK_DURATION=1m make benchmark-mixed RUN_ID=baseline-warmup   # discard
for i in 1 2 3; do
  BENCHMARK_VUS=15 BENCHMARK_DURATION=1m make benchmark-mixed RUN_ID="baseline-run-$i"
done
```

Compare the three `summary.md` files under `infrastructure/docker/k6/results/baseline-run-*/`.
Report the per-run p50/p95/p99 and note the spread between runs — do not treat a difference
smaller than that spread as a real change. State explicitly whether this was a cold start (first
request after `make incident-up`) or warm (JIT/connection-pool/cache state already settled from
the warm-up run above) — this benchmark always treats the warm-up run as required and discards
its numbers, so every reported baseline is warm.

## Reading raw results and dashboards

Each run writes to `infrastructure/docker/k6/results/<runId>/<scenario>-<timestamp>/`:

- `environment.md` — git/host/Docker/config capture.
- `k6-summary.json` — k6's own `--summary-export` (raw, machine-readable).
- `metrics-samples.csv` — timestamped samples of outbox backlog/oldest-pending-age/dead-letter/
  retry counters, HikariCP pool usage, process CPU, JVM heap, and auth/authz/audit-failure
  counters, scraped directly from incident-service's `/actuator/prometheus`.
- `summary.md` — a human-readable rollup of both, generated automatically.

This directory is git-ignored (`infrastructure/docker/k6/results/*`) — raw results are local
artifacts, not repository content. If the `observability` Compose profile is also running
(`make observability-up`), the existing Grafana dashboards (`docs/development/observability.md`)
show the same metrics live, correlated with traces and logs, for the duration of a run.

### Distinguishing HTTP latency from downstream completion

k6 only measures HTTP acceptance latency (time to the `201`/`200` response). The transactional
outbox's actual publish-to-Kafka step is asynchronous background work (ADR 0007) and is not
observable through the HTTP API — it is measured separately, via
`sentinelops_outbox_publish_duration_seconds` (already emitted by `IncidentMetrics`) and
`sentinelops_outbox_oldest_pending_age_seconds`, both sampled into `metrics-samples.csv`. Treat
the k6 numbers and the metrics-samples numbers as two different things measuring two different
steps — never add them together or present one as a substitute for the other.

## Demo sequence

```bash
make incident-up
make benchmark-seed RUN_ID=demo SIZE=50 SEED=42
make benchmark-smoke                                   # confirms viewer/responder/admin behavior
BENCHMARK_VUS=10 BENCHMARK_DURATION=45s \
  make benchmark-mixed RUN_ID=demo                      # a workload actually running
# While that's running, in another terminal:
#   curl -s -u "$ACTUATOR_METRICS_USERNAME:$ACTUATOR_METRICS_PASSWORD" \
#     http://127.0.0.1:8081/actuator/prometheus | grep sentinelops_outbox
make benchmark-burst                                     # the burst + recovery scenario
cat infrastructure/docker/k6/results/*/burst-recovery-*/summary.md   # backlog draining afterward
make benchmark-cleanup RUN_ID=demo
```

## Local-environment limitations

- Single developer laptop, shared CPU/memory with everything else running on it — numbers are
  not comparable across machines or to any production environment.
- No TLS, no rate limiting anywhere in the local stack (unchanged from Phase 7).
- k6 itself consumes CPU/memory on the same host it is generating load against; at high VU counts
  the load generator can become the bottleneck rather than the service under test — watch the k6
  container's own resource usage, not just incident-service's.
- The `burst-recovery` scenario deliberately has no hard latency/error-rate threshold (see the
  script's own comment) — a burst is expected to produce some backpressure; this scenario reports
  what happened rather than asserting a specific tolerance.

## CI

A short, bounded smoke benchmark (`smoke.js`) runs in CI on demand (`workflow_dispatch`), not on
every push — see `.github/workflows/ci.yml`'s `benchmark-smoke` job and its comment for why
extended/load scenarios stay opt-in rather than gating every PR on a shared runner's timing.
