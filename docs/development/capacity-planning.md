# Capacity planning (Phase 16)

**This document derives every number it states from Phase 15's actual validation run
(`docs/validation/latest-results.md`) or from explicit, labeled defaults — it does not invent a
throughput or latency figure that was never measured.**

## What Phase 15 actually measured

Phase 15's `run-validation.sh` executed in a Docker-unavailable sandbox, so **no k6 load
scenario was ever run against a live SentinelOps deployment** — see
`docs/validation/load-testing.md`'s explicit statement that no absolute-number baseline exists.
What Phase 15 did verify:

- The backend test suite (286 tests) and frontend test suite (50 tests) pass with zero
  regressions, confirming functional correctness under the tested code paths.
- All 11 chaos experiments dry-run correctly (command construction and control flow), and the
  `chaos-smoke` CI job runs three of them live against the Compose stack on every push.
- Every k6 scenario file declares its own pass/fail thresholds (e.g.
  `http_req_failed rate<0.02`, `p(95)<1000ms` for `sustained-telemetry-ingestion.js`) — these
  are the platform's *stated intent*, enforced automatically by
  `.github/workflows/ci.yml`'s `performance-regression-gate` job, not yet a *measured* result.

## How to actually establish a capacity baseline

1. Run `infrastructure/docker/scripts/benchmark-run.sh <scenario>` for each of the 12 scenarios
   under `infrastructure/docker/k6/scenarios/` against a representative environment (staging,
   sized like the `values-staging.yaml` Helm overlay).
2. Record the resulting `report.md`/`summary.md` output — throughput, p50/p95/p99 latency, error
   rate, and (from `benchmark-run.sh`'s Prometheus sampling) outbox backlog, HikariCP pool
   usage, and JVM/CPU.
3. Compare against each scenario's declared threshold; if a scenario passes with significant
   headroom, that headroom (not the threshold itself) is the actual capacity number to report.
4. Repeat under the `chaos-experiment.sh` fault conditions (see
   `docs/validation/load-testing.md`'s "recovery after dependency restoration" section) to learn
   the platform's degraded-mode capacity, not only its steady-state capacity.

## Sizing defaults (starting points, not measured guarantees)

The Helm chart's default `resources.requests`/`limits` and HPA target utilization
(`values.yaml`) are conservative, general-purpose Spring Boot sizing (250m/512Mi request,
1 CPU/1Gi limit for incident-service; 70% CPU / 80% memory HPA targets) — reasonable to start
from, but explicitly **not validated against a measured production workload** in this
repository. Adjust them once step 1–4 above produces real numbers for your traffic pattern, and
update this section (with the measurement date and environment) rather than leaving these as
permanent placeholders.

## Known scaling levers, in the order this platform would reach for them

1. `HorizontalPodAutoscaler` (already configured) — first response to sustained CPU/memory
   pressure on either service.
2. Kafka consumer concurrency / partition count (`REDPANDA_TOPIC_PARTITIONS` /
   `infrastructure/docker/redpanda/init-topics.sh`) — if consumer lag becomes the bottleneck
   rather than CPU.
3. RDS instance class / read replicas — if PostgreSQL connection/CPU pressure appears under
   `postgres-fault`-adjacent load (see the chaos catalog's `postgres-fault` experiment for the
   failure mode this addresses).
4. AI-triage rate limiting (`sentinelops.ai.rate-limit.cooldown`) already exists specifically to
   bound AI-provider load independent of overall request volume — see
   `ai-triage-saturation.js`'s own doc comment.
