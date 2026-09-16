---
slug: database-connection-exhaustion
title: Database Connection Pool Exhaustion
version: 1
owner: platform-team
last_reviewed: 2026-01-15
services: incident-service, telemetry-correlation-service, postgres
signals: hikaricp_connections_pending, hikaricp_connections_active, connection-timeout errors
---

## Symptoms

Requests or background jobs fail or hang with a connection-acquisition timeout
(`DB_CONNECTION_TIMEOUT_MS` exceeded), and `hikaricp_connections_active` is at or near
`DB_POOL_MAX_SIZE` with `hikaricp_connections_pending` greater than zero. This is a distinct,
more severe condition than the general latency symptoms in the "Elevated API Latency" runbook —
here, requests fail outright rather than merely slow down.

## Safe diagnostic steps

1. Confirm the pool is actually exhausted (active ~= max, pending > 0) rather than the database
   itself being unreachable (see the "Service Health-Check Failure" runbook for that case — the
   `/actuator/health/readiness` `db` indicator distinguishes the two).
2. Check for a long-running or stuck query holding connections — query
   `pg_stat_activity` on the affected database for connections in a non-idle state for an
   unusually long duration.
3. Check whether a recent code change introduced a connection leak (a code path that acquires a
   connection without releasing it, e.g. missing a try-with-resources or transaction boundary).
4. Check whether traffic volume itself has genuinely increased beyond what the configured pool
   size supports, versus a smaller number of requests each holding a connection far too long.

## Escalation conditions

- Connection-acquisition timeouts are visibly affecting production request success rate.
- A suspected connection leak cannot be identified within the first investigation window.
- Restarting the affected service (which releases all its held connections) does not resolve the
  symptom, suggesting the root cause is external to this service (e.g. another service or a
  runaway query holding connections against the same database).

## Recovery considerations

- A service restart is a reasonable, low-risk mitigation for a suspected connection leak — it
  clears the affected pool immediately, at the cost of the restarted service's brief unavailability
  (already handled gracefully — see graceful shutdown in `docs/development/reliability.md`).
- Do not manually terminate database backend processes (`pg_terminate_backend`) as a first
  response — identify what the connection is doing first, since terminating a connection mid-write
  can leave application-level retry logic to handle the aftermath unnecessarily.
- Raising `DB_POOL_MAX_SIZE` is a valid longer-term fix if genuine traffic growth is the cause, but
  is not a substitute for fixing an actual leak.

## Verification steps

1. Confirm `hikaricp_connections_pending` has returned to zero and stayed there for at least 15
   minutes.
2. Confirm no new connection-timeout errors appear in logs for the same window.
3. If a leak was the suspected cause, confirm the specific code path was fixed and deployed, not
   just mitigated by a restart.
