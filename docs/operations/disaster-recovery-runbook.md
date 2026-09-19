# Disaster-recovery runbook

The mechanisms and the actual DR exercise procedure are documented in
`docs/validation/disaster-recovery.md` (Phase 15) — this page is the operator-facing runbook for
invoking them during a real incident, not a restatement of how they work.

## Declaring a disaster

A "disaster" here means: the primary PostgreSQL instance is confirmed lost or corrupted beyond
in-place recovery (not merely unreachable — see `docs/operations/incident-response.md` for a
plain connectivity issue, which is not a disaster-recovery event).

## Procedure

1. **Stop writes.** Scale `incident-service` and `telemetry-correlation-service` to zero
   replicas (`kubectl -n sentinelops scale deployment/incident-service --replicas=0`, likewise
   for the correlation service) so nothing writes against a database that's about to be
   replaced.
2. **Identify the most recent good backup.** In production, this is the most recent
   AWS-Backup/RDS-automated-snapshot; locally/staging, the most recent file from
   `infrastructure/docker/scripts/db-backup.sh`'s output directory.
3. **Restore into a fresh database** (never in place over a database you're unsure about) —
   `infrastructure/docker/scripts/db-restore.sh <backup> <newDbName>`, or the RDS
   point-in-time-recovery / snapshot-restore equivalent in production.
4. **Verify** with `infrastructure/docker/scripts/db-restore-verify.sh` (Flyway history +
   `integrity-check.sh`) before pointing the application at it.
5. **Repoint the application** — update `POSTGRES_HOST`/the Secrets Manager secret to the
   restored instance's endpoint.
6. **Scale services back up** and confirm `GET /actuator/health/readiness` is `UP` on both.
7. **Reconcile remediation executions.** Any execution that was `RUNNING` at the moment of data
   loss needs manual review — its state may not reflect what actually happened on the target
   system between the last committed transaction and the outage. Check each such execution's
   audit trail and the underlying target's actual state before assuming the recorded status is
   accurate.

## Measuring and reporting RTO/RPO for the real event

Use the same measurement method as the drill: RTO = wall-clock time from step 1 to step 6's
health confirmation; RPO = the age of the restored backup relative to the outage moment (every
write between the backup and the outage is lost). Record both in this file's incident log below,
exactly as measured — never rounded to look better than what happened.

### Real disaster-recovery events

_None have occurred — this repository has not been deployed to a real production environment.
See `docs/validation/disaster-recovery.md` for the drill's measured (not real-incident) RTO/RPO
methodology and its own honest statement of what has and has not actually been measured._
