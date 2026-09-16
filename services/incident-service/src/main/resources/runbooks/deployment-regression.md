---
slug: deployment-regression
title: Deployment Regression
version: 1
owner: platform-team
last_reviewed: 2026-01-15
services: incident-service, telemetry-correlation-service
signals: error-rate spike immediately after deploy, new exceptions in logs, failed readiness checks
---

## Symptoms

Error rate, latency, or a specific new exception pattern begins immediately (within minutes) after
a deployment completes. The correlation with deployment timing is the key distinguishing symptom —
without it, treat the issue under the more specific runbook that matches the actual symptom
(latency, database, messaging, auth).

## Safe diagnostic steps

1. Confirm the timing correlation precisely: compare the deployment completion timestamp against
   the first occurrence of the new symptom.
2. Check `/actuator/health/readiness` and recent logs for the newly deployed instance specifically
   — a failed migration or a bad configuration value often surfaces here first.
3. Check whether the new version introduced a database migration (see
   `docs/development/operations.md`'s migration section) — if so, confirm the migration itself
   applied successfully and did not fail partway.
4. Compare the new version's SBOM/dependency changes (see the CI `sbom` job) for anything
   unexpected if a dependency-related regression is suspected.

## Escalation conditions

- The regression affects a customer-facing workflow and does not self-resolve.
- The root cause is not identified within the release's own defined observation window (see
  `docs/development/operations.md`'s release procedure).

## Recovery considerations

- **Application rollback is the preferred first response** — redeploy the previous, known-good
  image. This is safe whenever the deployment's migrations were purely additive (see
  `docs/development/operations.md`'s migration backward-compatibility table) — check that table
  for the specific migration involved before rolling back if a migration was part of this release.
- **Database rollback/restore is never automatically safe** and is a separate, more drastic
  decision — see `docs/development/operations.md`'s "four distinct recovery actions" section. Do
  not restore a backup as a first response to a deployment regression.
- Prefer forward-compatible fixes over a database-level rollback whenever the schema change itself
  was not the cause.

## Verification steps

1. Confirm the error rate/latency/exception pattern has returned to its pre-deployment baseline.
2. Confirm readiness checks pass consistently for at least the release procedure's defined
   observation window.
3. If rolled back, confirm the rolled-back version is the one actually serving traffic (not a
   partial rollback with mixed versions).
