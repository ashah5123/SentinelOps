# Incident-response runbook (operating SentinelOps itself)

This describes how an operator responds to a *platform* incident (SentinelOps itself degraded or
down) using the tools this repository provides — distinct from how SentinelOps helps a team
respond to incidents in the services *it* monitors (that workflow is the product itself; see the
README's "Major capabilities" section and `docs/development/remediation.md`).

## Triage order

1. **Check Platform Health** (`/platform-health` in the console, or `GET /api/v1/slo/status`) —
   which SLO's error budget/burn-rate is degraded tells you which subsystem to focus on (see
   `docs/validation/slo.md`'s severity table: WARNING/CRITICAL burn rate means act now).
2. **Check dependency health**: `GET /actuator/health` on both services shows the readiness
   group's per-dependency status (`db`, `kafkaConnectivity`) directly.
3. **Check Grafana** (`infrastructure/docker/observability/grafana/dashboards/`) for the
   relevant dashboard: `sentinelops-service-overview`, `sentinelops-incident-processing`,
   `sentinelops-reliability`, or `sentinelops-telemetry-correlation`.
4. **Check for an active chaos experiment** — `infrastructure/docker/scripts/chaos-experiment.sh`
   always restores the environment automatically (see `docs/validation/chaos-engineering.md`),
   but if a manual experiment was left running past its documented duration, stop it:
   `docker compose ... unpause <service>` (or `kubectl -n sentinelops delete pod` to force a
   fresh restart in a real cluster).

## Common platform incidents and the runbook for each

| Symptom | Runbook |
| --- | --- |
| Ingestion backlog growing (outbox/consumer lag) | `docs/development/operations.md` §11 (broker/consumer troubleshooting) |
| A remediation execution stuck | Check `GET /api/v1/remediations/{id}` — if `RUNNING` for longer than its runbook's total step timeout, see `docs/development/upgrade-and-rollback.md`'s "interrupted remediation executions" section |
| Database unreachable | `docs/validation/disaster-recovery.md` (only if data loss is suspected) or `docs/development/upgrade-and-rollback.md`'s troubleshooting table (if it's a connectivity/pod issue, not data loss) |
| AI-triage unavailable | Expected to degrade gracefully — see `docs/development/ai-triage.md`'s fallback behavior; confirm incidents are still being created/correlated without AI (they must be — see the Phase 15 `llm-fault` chaos experiment and `ChaosInjectingAiProviderTest`) |
| Notification delivery stalled | Check Mailpit/webhook-sink health; see `docs/development/alert-ingestion.md`'s retry/dead-letter section |

## Escalation

See `docs/operations/ownership-and-escalation.md` for who owns what and when to escalate beyond
the on-call operator.

## Post-incident

Every platform incident should result in: an audit-trail review (`GET /api/v1/audit`, or the
console's audit view) covering the incident window, and — if a chaos experiment or a real
dependency failure was involved — a note in this file's "lessons learned" log below.

### Lessons learned log

_No production platform incidents have occurred yet — this repository has not been deployed to a
real production environment. This section is a template to be filled in as real operational
history accumulates, not a record of fabricated past incidents._
