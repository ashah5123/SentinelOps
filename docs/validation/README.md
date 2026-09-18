# Reproducible validation evidence (Phase 15)

## The single command

```bash
infrastructure/docker/scripts/run-validation.sh
```

Prepares `.env` (copying `.env.example` and enabling `CHAOS_EXPERIMENTS_ENABLED` if not already
set), then runs, in order:

1. Backend static analysis (spotless + spotbugs) and unit/integration tests.
2. Frontend lint, typecheck, tests, and production build.
3. A `--dry-run` pass of the entire chaos-experiment catalog (always runs — no Docker required).
4. If Docker is available: the disaster-recovery exercise, the MinIO backup/restore
   verification, two live chaos experiments with a post-recovery health check, and a capture of
   the live SLO status. If Docker is unavailable, each of these is recorded as explicitly
   `skipped` (never silently omitted, never faked as `pass`).

Every step's real stdout/stderr is saved under
`infrastructure/docker/results/validation/<runId>/` (git-ignored — see `.gitignore`), alongside
two generated reports:

- `report.json` — machine-readable, matching `docs/validation/report-schema.json`.
- `report.md` — the human-readable Markdown summary.

The script exits non-zero if any check's status is `fail`.

## `docs/validation/latest-results.md`

This file is created **by hand**, copied from a specific run's `report.md`, and only when that
run's `report.json` contains zero `fail` entries. It is never generated automatically as part of
`run-validation.sh` itself — that would risk a partial or failing run silently overwriting the
last known-good evidence. If the file is absent, no run in this environment has yet completed
with zero failures; check the phase completion report for the exact blocker.

Every number in `latest-results.md` is labeled as a **local benchmark/validation result**, never
a production SLA or capacity claim — see the disclaimer at the top of that file (and of
`PlatformHealth.tsx`'s console page) for the exact wording.

## What this pipeline does not (yet) do

- It does not run CI's `codeql`, `secret-scan`, or `zap-baseline-scan` jobs locally — those
  require the GitHub Actions runner environment (network egress to GitHub's Security tab
  integration, ZAP's own container image) and are documented separately in
  `docs/validation/security-validation.md`.
- It does not run a full k6 load scenario (that requires the "benchmark" Compose profile and
  takes minutes per scenario) — see `docs/validation/load-testing.md` for how to run one
  alongside a chaos experiment to observe "recovery after dependency restoration."
- It does not commit or push anything itself.

## Related documents

- `docs/validation/chaos-engineering.md` — the chaos-experiment catalog and safety mechanisms.
- `docs/validation/slo.md` — SLO/error-budget definitions and burn-rate math.
- `docs/validation/disaster-recovery.md` — backup/restore mechanisms and the DR exercise.
- `docs/validation/security-validation.md` — CI security tooling and accepted-risk notes.
- `docs/validation/load-testing.md` — new k6 scenarios and baseline methodology.
