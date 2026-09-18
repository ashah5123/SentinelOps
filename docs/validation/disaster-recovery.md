# Disaster recovery (Phase 15)

## What is backed up, and how

| Data | Mechanism | Script |
| --- | --- | --- |
| PostgreSQL (incidents, audit, telemetry, runbooks schemas) | `pg_dump` custom-format, compressed | `infrastructure/docker/scripts/db-backup.sh` (Phase 9) |
| Audit records | Included in the PostgreSQL dump above — `audit.audit_events` is not backed up separately | same |
| Configuration & policy versions (routing rules, remediation runbooks, policy engine config) | Version-controlled in git — the repository itself is the backup/restore mechanism | N/A (git) |
| Object-storage artifacts (MinIO buckets) | `mc`-based object round-trip verification | `infrastructure/docker/scripts/minio-backup-restore-verify.sh` (Phase 15) |
| Grafana dashboards & alert rules | Version-controlled JSON/YAML under `infrastructure/docker/observability/` — provisioned from git on every container start, so "restore" is re-provisioning from the committed files | N/A (git) |

## The DR exercise

`infrastructure/docker/scripts/dr-exercise.sh` runs a real drill against an isolated, disposable
database (never the live one):

1. Insert a marker row, then take a backup (T0).
2. Insert 5 more rows *after* T0 (simulating writes that happen after the last backup).
3. Start the recovery clock ("disaster declared").
4. Restore the T0 backup into a disposable database and run the full integrity check
   (`db-restore-verify.sh`, which itself checks Flyway migration history and representative row
   counts).
5. Stop the clock once the restore is verified healthy — this elapsed time is **RTO**.
6. Independently restore the same backup a second time (kept, not auto-dropped) and count how
   many of the 5 post-backup rows are present (expected: 0) — this row count and its time window
   is **RPO**: real, measured data loss for this backup cadence, not an assumed number.

```bash
infrastructure/docker/scripts/dr-exercise.sh
```

## Remediation-execution recovery

A remediation execution interrupted mid-flight (e.g. incident-service restarts between two
steps) must resolve to a safe, auditable terminal state — never silently abandoned in `RUNNING`.
This is exercised by the `remediation-execution-fault` chaos experiment (see
`docs/validation/chaos-engineering.md`) and was already covered at the state-machine/scheduler
level by Phase 14's `RemediationExecutionSchedulerTest` (the automatic-rollback-on-failed-health-
check scenario) plus the pre-existing `RestartDurabilityIntegrationTest`.

## Verification performed for this phase

`dr-exercise.sh` and `minio-backup-restore-verify.sh` were syntax-checked (`bash -n`) and
reviewed against the established `db-backup.sh`/`db-restore.sh`/`db-restore-verify.sh` scripts
they build on, but **not executed against a live PostgreSQL/MinIO instance in this sandbox** (no
Docker available). `.github/workflows/ci.yml`'s new `dr-exercise` job runs both scripts against
the real Compose stack on every push — that CI run is the actual RTO/RPO measurement this phase
produces; this document intentionally does not state a specific RTO/RPO number, since none has
been measured in this environment (see `docs/validation/latest-results.md`'s absence, explained
in the phase completion report, for why no fabricated number appears here).
