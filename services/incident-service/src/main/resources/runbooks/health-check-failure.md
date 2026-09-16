---
slug: health-check-failure
title: Service Health-Check Failure
version: 1
owner: platform-team
last_reviewed: 2026-01-15
services: incident-service, telemetry-correlation-service
signals: /actuator/health/readiness or /actuator/health/liveness returning DOWN, container restarts
---

## Symptoms

`/actuator/health/readiness` or `/actuator/health/liveness` returns a non-UP status, or a
container's own health check (see the Dockerfile `HEALTHCHECK`) begins failing, potentially
triggering repeated restarts. Readiness failing specifically means PostgreSQL or the
Kafka-compatible broker is unreachable (see
[ADR 0011](../decisions/0011-reliability-and-failure-recovery.md)) — liveness failing means the
process itself is unhealthy and should be restarted.

## Safe diagnostic steps

1. Distinguish readiness from liveness first — they mean different things and call for different
   responses (see above).
2. For a readiness failure, check PostgreSQL and Redpanda container health directly
   (`make infra-status`) — readiness intentionally mirrors their actual reachability, not just the
   application's own internal state.
3. For a liveness failure, check the application's own logs for an unrecoverable error (e.g. an
   out-of-memory condition) rather than assuming it is a dependency issue.
4. Check whether this coincides with a recent deployment (see the "Deployment Regression"
   runbook) or a planned dependency restart/maintenance window.

## Escalation conditions

- Readiness remains DOWN for longer than the dependency's own expected recovery time (e.g. longer
  than a routine Postgres/Redpanda restart should take).
- Liveness failures recur repeatedly even after a restart, suggesting a persistent, not transient,
  problem.
- The failure is affecting customer-facing availability, not just an internal/admin surface.

## Recovery considerations

- If the dependency (Postgres/Redpanda) itself is down, restore it first — the application's
  readiness check is working as designed by reporting DOWN in that case, and "fixing" the check
  itself would hide a real problem.
- A liveness failure that resolves after a restart, with no recurrence, does not necessarily need
  further investigation — but should still be noted, since orchestrated restarts that recur are a
  symptom of an underlying issue, not a fix.
- Do not disable or loosen a health check to make an alert go away — see
  `docs/development/reliability.md`'s reliability guarantees.

## Verification steps

1. Confirm `/actuator/health/readiness` and `/actuator/health/liveness` both report UP and stay
   that way for at least 15 consecutive minutes.
2. Confirm no further unplanned container restarts occur in the same window.
3. If a dependency outage was the cause, confirm the dependency itself is verified healthy, not
   just that the application's own check passed once.
