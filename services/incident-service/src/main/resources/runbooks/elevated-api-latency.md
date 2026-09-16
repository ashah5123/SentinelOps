---
slug: elevated-api-latency
title: Elevated API Latency
version: 1
owner: platform-team
last_reviewed: 2026-01-15
services: incident-service, telemetry-correlation-service
signals: p95/p99 HTTP request latency, http_server_requests_seconds_*, database connection pool wait time
---

## Symptoms

Requests to a service's REST API take noticeably longer than baseline (see the "SentinelOps
Service Overview" Grafana dashboard). Responders typically notice this via elevated p95/p99
latency alerts, slow dashboard load times, or user/operator reports of "the console feels slow."
Error rate is not necessarily elevated — latency and errors are separate symptoms with different
runbooks.

## Safe diagnostic steps

1. Check the affected service's request-latency panel in Grafana ("SentinelOps Service Overview")
   to confirm which endpoint(s) and time window are affected.
2. Check the HikariCP connection-pool metrics (`hikaricp_connections_active`,
   `hikaricp_connections_pending`) for the same window — a saturated pool (active near
   `DB_POOL_MAX_SIZE`, pending greater than zero) points at database contention rather than the
   application itself.
3. Check Tempo for a representative slow trace from the affected window and identify which span
   (application code, database query, downstream Kafka call) accounts for most of the duration.
4. Check whether the slowdown coincides with a recent deployment (see the "Deployment Regression"
   runbook) or a known batch/maintenance job.
5. Check outbox backlog metrics (`sentinelops_outbox_backlog`,
   `sentinelops_outbox_oldest_pending_age_seconds`) — a large backlog can indicate the same
   underlying resource contention is also slowing outbox publishing.

## Escalation conditions

- p99 latency exceeds 2x its 7-day baseline for more than 10 consecutive minutes.
- The slowdown is confirmed to affect a customer-facing workflow, not just an internal/admin one.
- The connection pool is saturated (pending > 0) and does not recover after the query traffic that
  caused it stops.

## Recovery considerations

- If a specific slow query is identified, consider whether a missing or unused index is the root
  cause (see `docs/development/operations.md`'s migration section for how indexes are added).
- If the connection pool is undersized for current load, `DB_POOL_MAX_SIZE` can be raised via
  configuration — this requires a coordinated restart, not a live change.
- If a recent deployment is the cause, prefer an application rollback (see
  `docs/development/operations.md`'s release/rollback procedure) over attempting a live fix.

## Verification steps

1. Confirm p95/p99 latency has returned to within its normal baseline range for at least 15
   consecutive minutes.
2. Confirm the connection pool's pending-connections metric has returned to zero.
3. Confirm no new latency-related alerts have fired since the fix was applied.
