# Load and performance testing (Phase 15 additions)

## New k6 scenarios

Added under `infrastructure/docker/k6/scenarios/`, alongside the existing Phase 8 suite (`smoke`,
`paginated-reads`, `incident-creation`, `lifecycle-transitions`, `mixed-workload`,
`burst-recovery`, `unauthorized-access`):

| Scenario | Covers |
| --- | --- |
| `sustained-telemetry-ingestion.js` | Steady-state alert/telemetry ingestion at a constant arrival rate (distinct from `burst-recovery.js`'s short spike) |
| `ai-triage-saturation.js` | AI-triage request queueing/latency under concurrency (fresh incident per iteration, since generation is rate-limited per incident) |
| `concurrent-remediation-requests.js` | Concurrent remediation proposals against the same auto-approving LOW-risk runbook |
| `notification-fanout.js` | Sustained high-severity alert volume designed to fan out across multiple notification channels |
| `console-polling.js` | N simulated open console tabs, each repeating the Dashboard's exact poll set on an interval — models "live console updates" honestly, since the console has no WebSocket/SSE endpoint (confirmed: `frontend/src/hooks/usePolling.ts`'s own doc comment) |

"Concurrent incident reads and updates" is already covered by the existing `mixed-workload.js`
(≈70% reads / 30% writes against independently-owned incidents) — not duplicated here.

## Recovery after dependency restoration

There is no separate k6 scenario for this — it is a *combined* usage of an existing sustained
scenario and the new chaos framework, following the same "acceptance is separate from recovery"
principle `burst-recovery.js` already established:

```bash
# Terminal 1: sustained load
infrastructure/docker/scripts/benchmark-run.sh sustained-telemetry-ingestion

# Terminal 2, partway through: interrupt a dependency
infrastructure/docker/scripts/chaos-experiment.sh kafka-broker-fault
```

`benchmark-run.sh`'s own post-run `RECOVERY_WINDOW_SECONDS` sampling then captures how quickly
backlog/latency metrics return to baseline once the chaos experiment's unconditional recovery
step restores the dependency.

## Baseline and regression thresholds

Per-scenario thresholds (e.g. `http_req_failed rate<0.02`, latency percentiles) are already
declared in each scenario file's `options.thresholds` — k6 itself fails the run if they are
violated, which is the actual regression-enforcement mechanism (not a separate comparison step).

**No absolute-number baseline exists for the five new scenarios**, and none is fabricated here.
`docs/benchmarks/phase-8-performance.md` already documents that no k6 run has ever been executed
in this project's authoring sandbox (Docker unavailable) — that constraint is unchanged in this
phase. A real baseline requires running `infrastructure/docker/scripts/benchmark-run.sh
<scenario>` against a live stack (CI's `benchmark-smoke`/`benchmark-extended` jobs, or a local
machine with Docker) and committing the resulting numbers — see
`docs/validation/README.md`'s reproducible-evidence pipeline for where that output should land.

## Profiling

- **Java** (`incident-service`, `telemetry-correlation-service`): attach `async-profiler` or use
  `jcmd <pid> JFR.start` while a k6 scenario runs against a locally started service (`./mvnw
  spring-boot:run`), then load the resulting `.jfr` file in JDK Mission Control. No code change
  is required to enable this — both are attach-time tools.
- **No Python services exist in this repository** (the only Python is a handful of local
  development scripts, e.g. `infrastructure/docker/webhook-sink/server.py`); the "Python
  services" profiling instruction in the prompt does not apply to this codebase's actual stack.

## Verification performed for this phase

All five new scenario files were syntax-checked with `node --input-type=module --check` (k6's
own binary is unavailable in this sandbox) and reviewed against the established scenario
conventions (shared `lib/auth.js`/`lib/common.js`, per-scenario thresholds, acceptance-vs-
completion separation). None was executed against a live k6/incident-service stack in this
sandbox.
