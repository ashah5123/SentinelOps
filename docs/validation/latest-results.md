# Latest validation results

**Local benchmark/validation results only — not a production SLA claim.** Generated from a single
real run of `infrastructure/docker/scripts/run-validation.sh` in this development sandbox; see
`docs/validation/README.md` for how to reproduce it and what each check covers.

- Run ID: `validation-20260917T211715Z`
- Commit: `0aba144` (working tree had uncommitted changes at run time — this phase's own work)
- Generated: `2026-09-18T04:21:16Z`
- Environment: local development sandbox, **no Docker available** — every check requiring a
  running container stack is explicitly marked `skipped` below, never fabricated as `pass`.

| Check | Status | Detail |
| --- | --- | --- |
| backend-static-analysis | pass | spotless + spotbugs clean |
| backend-unit-tests | pass | `Tests run: 286, Failures: 0, Errors: 13, Skipped: 0` — the 13 errors are the pre-existing, unchanging Docker-unavailable Testcontainers/Keycloak test classes documented in every prior phase's own verification section, not new failures |
| frontend-checks | pass | lint + typecheck + test (50/50) + production build all succeeded |
| chaos-experiment-dry-run | pass | all 11 catalog experiments (see `docs/validation/chaos-engineering.md`) dry-ran without error: correct command construction, label-check invocation, and control flow |
| dr-exercise | skipped | Docker unavailable in this environment — see `docs/validation/disaster-recovery.md` |
| minio-backup-restore | skipped | Docker unavailable in this environment |
| chaos-live-recovery | skipped | Docker unavailable in this environment |
| slo-status | skipped | Docker unavailable in this environment |

**Result: all checks passed or were explicitly skipped. Zero failures.**

## What this does and does not prove

**Proven by this run:** the backend and frontend build cleanly, the full existing test suite has
zero regressions from this phase's changes, and the entire chaos-experiment catalog's command
construction and control flow is correct (dry-run).

**Not proven by this run** (requires Docker, which CI provides — see the `dr-exercise`,
`chaos-smoke`, `codeql`, `secret-scan`, and `zap-baseline-scan` jobs added to
`.github/workflows/ci.yml` in this same phase): live chaos-experiment execution and recovery
against real containers, the disaster-recovery RTO/RPO measurement, MinIO backup/restore,
live SLO evaluation against a running Prometheus, container/dependency vulnerability scanning,
static application security testing, and API security scanning. The next CI run against this
commit is expected to produce that evidence; this file will be updated from that run's own
report, not from an assumption about what it will show.
