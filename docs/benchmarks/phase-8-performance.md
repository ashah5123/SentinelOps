# Phase 8 Performance Testing and Reproducible Benchmarks

Status: Phase 8. This document records **actual measurements only**. **No benchmark in this
document was executed** — Docker is not installed in the environment this phase was authored in
(confirmed: `docker`, `docker compose`, `k6`: command not found), which blocks every scenario
described here, exactly as it has blocked runtime verification for every prior phase (see
`docs/benchmarks/phase-6-reliability.md`). Everything in this phase — the benchmark harness, the
seeding/cleanup tooling, the k6 scenarios, and the CI wiring — was built and statically validated
(shell syntax, JS syntax, YAML/JSON parsing, and dry-running every piece of logic that does not
itself require Docker), but **no baseline was measured, no optimization was made, and no
before/after comparison exists.** Section "What was actually run" below lists exactly what could
be verified without Docker, and what remains to be done once it is available.

See `docs/development/performance.md` for the full setup/usage guide.

## Why no optimization was attempted

The task calls for identifying a bottleneck via "traces, query plans, logs, and resource metrics"
from an actual run, and for reporting before/after numbers under comparable conditions. With no
way to run the application, none of that evidence exists. Implementing a code change anyway and
presenting it as "the optimization" would mean inventing a before/after story with no
measurements behind it — exactly what this phase's own instructions prohibit ("Do not claim
improvements... without explaining uncertainty," "Never invent measurements"). Per the phase's own
fallback ("If no defensible optimization is identified, document the baseline and findings without
inventing an improvement"), no code change was made to the incident service's request-handling or
persistence logic as part of this phase.

One static, code-and-schema-level observation is recorded below as a **candidate for
investigation**, not as an implemented or validated optimization:

- `GET /api/v1/incidents` supports filtering by `status`, `severity`, `affectedService`, and a
  `detectedAt` range simultaneously (`IncidentSpecifications`), but the `incidents` table only has
  single-column indexes on each of those fields individually (`V1__create_incidents_table.sql`) —
  no composite index covering a common combination (e.g. `affected_service, detected_at`) or
  matching the default sort (`detected_at DESC, id`). Whether this is actually a bottleneck at any
  realistic dataset size, and what composite index (if any) would help, is exactly the kind of
  thing `EXPLAIN ANALYZE` against the seeded benchmark dataset would answer — and exactly what
  could not be checked here. This is flagged for whoever runs the baseline in this environment,
  not implemented speculatively.

## What was actually run (static validation only)

```bash
# Shell syntax checks (bash -n) — all passed:
bash -n infrastructure/docker/scripts/benchmark-seed.sh
bash -n infrastructure/docker/scripts/benchmark-cleanup.sh
bash -n infrastructure/docker/scripts/benchmark-env-info.sh
bash -n infrastructure/docker/scripts/benchmark-run.sh
bash -n infrastructure/docker/scripts/lib/auth.sh
bash -n infrastructure/docker/scripts/reliability-fault-test.sh   # after Phase-7-auth fixes
bash -n infrastructure/docker/scripts/correlation-smoke-test.sh   # after Phase-7-auth fixes
bash -n infrastructure/docker/scripts/observability-smoke-test.sh # after Phase-7-auth fixes

# k6 (JavaScript) syntax checks (node --check against a temporary .mjs copy) — all passed:
# lib/auth.js, lib/common.js, scenarios/{smoke,paginated-reads,incident-creation,
# lifecycle-transitions,mixed-workload,burst-recovery,unauthorized-access}.js

# Docker Compose / realm JSON parse checks — all passed:
python3 -c "import yaml; yaml.safe_load(open('infrastructure/docker/docker-compose.yml'))"

# The env-info and Makefile-wiring logic itself (the parts that don't need a live stack) —
# actually executed and produced correct output:
make benchmark-env-info

# The metric-parsing and summary-generation logic inside benchmark-run.sh — unit-verified against
# hand-written fake Prometheus text output and a fake k6 --summary-export JSON file (not the real
# incident-service/k6, since neither can run here); both produced correct, bug-fixed output (an
# initial JVM-heap-metric parsing bug was caught and fixed this way before ever touching a real
# run).

# Repository-level checks:
make validate
```

Commands that **could not** be run (Docker/k6 unavailable), listed here for completeness:

```bash
make incident-up                                   # brings up Postgres/Redpanda/Keycloak/app
make benchmark-seed RUN_ID=... SIZE=200 SEED=42
make benchmark-smoke
make benchmark-unauthorized
make benchmark-reads RUN_ID=...
make benchmark-create
make benchmark-lifecycle
make benchmark-mixed
make benchmark-burst
make benchmark-cleanup RUN_ID=...
docker compose --env-file .env -f infrastructure/docker/docker-compose.yml \
  --profile app --profile benchmark config --quiet
```

## A regression this phase found and fixed (Phase 7 broke Phase 5/6's own test scripts)

While verifying what Phase 6/7 actually left working (per this phase's own instruction to "verify
what is implemented... do not assume previous prompts were completed"), static inspection found
that Phase 7's authentication requirement broke three pre-existing local scripts that call
`incident-service`'s now-authenticated API without a bearer token, and one that scraped
`/actuator/prometheus` without the Basic-auth credentials Phase 7 added:

- `infrastructure/docker/scripts/reliability-fault-test.sh` (Phase 6) — every
  `/api/v1/incidents` call.
- `infrastructure/docker/scripts/correlation-smoke-test.sh` (Phase 5) — the incident-creation and
  timeline calls.
- `infrastructure/docker/scripts/observability-smoke-test.sh` (Phase 4) — the
  `/actuator/prometheus` scrape check.

All three were fixed (a shared `infrastructure/docker/scripts/lib/auth.sh` helper fetches a real
`responder-demo` token via the same Resource Owner Password Credentials flow documented in
`docs/development/security.md`; the observability script's metrics check now sends the configured
Basic-auth credentials). Two of the affected `curl` payloads in `reliability-fault-test.sh` and one
in `correlation-smoke-test.sh` were also missing the `detectedAt` field `CreateIncidentRequest` has
required since Phase 3 — a second, unrelated pre-existing bug that would have failed validation
regardless of authentication, also fixed. None of these fixes could be confirmed by actually
running the scripts (same Docker blocker), only by static reading against the current
`SecurityConfig`/`CreateIncidentRequest` source.

## Limitations of this report

- Every scenario, every metric, and every optimization claim in `docs/development/performance.md`
  is prospective — written to be correct and ready to run, not reporting something that happened.
- No p50/p95/p99 latency, throughput, error rate, outbox backlog, or resource-usage number in this
  document is a real measurement; none should be treated as one.
- This report must be replaced (not appended to) with real results once Docker is available —
  following exactly the reproduction commands in `docs/development/performance.md`.
