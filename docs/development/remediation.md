# Policy-controlled automated remediation engine

Phase 14 converts an approved proposal (Phase 13's `agent_proposals`, or a direct console
request) into a safe, observable, reversible operational action: restart/scale/rollback a
deployment, toggle a feature flag, clear a cache namespace, pause/resume a queue, or run one
allowlisted diagnostic command — never an arbitrary shell command, never unrestricted cluster
access.

## Architecture

```
runbook (versioned YAML)
        │
        ▼
RemediationExecutionService.propose()
   ├─ loads the active runbook, parses+validates its YAML
   ├─ computes blast radius (adapter.plan() on every step, deduplicated resource set)
   ├─ evaluates PolicyEngine → ALLOW / DENY / REQUIRE_APPROVAL
   └─ inserts a remediation_executions row (PROPOSED) + one remediation_steps row per forward step
        │
        ├─ ALLOW ─────────────────► auto: PROPOSED → APPROVED → SCHEDULED
        ├─ REQUIRE_APPROVAL ──────► stays PROPOSED until enough distinct approvers call approve()
        └─ DENY ──────────────────► PROPOSED → DENIED (terminal, fully audited)
        │
        ▼ (SCHEDULED)
RemediationExecutionScheduler (@Scheduled poller)
   ├─ claims due rows (distributed lock + guarded status transition, so two instances never
   │   double-run the same execution)
   ├─ runs each forward step: adapter.execute() (or .plan() if dry-run) with per-adapter-type
   │   circuit breaker + retry/backoff, checking cancel_requested/emergency_stop between steps
   ├─ captures health before and after (HealthCheckAdapter against the runbook's declared target)
   └─ on any step failure OR a failed post-execution health check: runs the runbook's
      rollbackSteps automatically → ROLLED_BACK; otherwise → SUCCEEDED
```

Every layer reuses an established pattern rather than inventing a new one:

| Concern | Reused from |
| --- | --- |
| Durable async processing, guarded status transitions | Outbox pattern (`OutboxPublisher`), `EscalationScheduler` |
| JDBC + raw SQL for JSONB columns | `AgentProposalRepository`, `AlertEventRepository` |
| Idempotent insert / distributed lock | `AlertFingerprintRepository`'s `ON CONFLICT DO NOTHING` idiom |
| Per-call circuit breaker | `SimpleCircuitBreaker` (Phase 11), one instance per `RemediationActionType` |
| Two-stage propose → approve execution model | Phase 13's `AgentProposalService`/`AgentApprovalService` |
| `@ConfigurationProperties` + binding test | Every prior phase's properties class |

## Local implementations (no real cluster/broker/cache is available here)

Per the spec's "use mock or local containerized infrastructure for demonstrations," every adapter
that would otherwise need a real external system is backed by a small, DB-row-backed simulation
with the same observable state semantics as the real thing:

| Adapter | Simulated backing store | What a production adapter would swap in |
| --- | --- | --- |
| `KubernetesRestartAdapter`, `KubernetesScaleAdapter`, `KubernetesRollbackAdapter`, `HealthCheckAdapter` | `simulated_deployments` (replicas, revision, bounded revision history, `healthy` flag) | A real Kubernetes client (`fabric8`/`client-go` equivalent) against the actual Deployment |
| `FeatureFlagDisableAdapter`, `FeatureFlagEnableAdapter` | `simulated_feature_flags` | A real flag-service SDK call |
| `CacheClearAdapter` | `simulated_cache_entries` | A real Redis/Memcached `DEL`/`FLUSHDB` against the allowlisted namespace |
| `QueuePauseAdapter`, `QueueResumeAdapter` | `simulated_queues` | A real broker admin API call |
| `DiagnosticCommandAdapter` | N/A — runs a real, fixed-argv `ProcessBuilder` command from `sentinelops.remediation.allowed-diagnostic-commands` | Unchanged; already real, just tightly allowlisted |

Every adapter implements the same `RemediationActionAdapter` interface (`type()`, `plan()`,
`execute()`), so swapping a simulation for a real client is a drop-in replacement with no change
to the policy engine, state machine, or scheduler.

**Fault-injection hook for the rollback demo:** `SimulatedDeploymentRepository.setHealthy(service,
environment, false)` (also reachable via a direct `UPDATE incidents.simulated_deployments SET
healthy = false ...` — see `remediation-demo.sh`) is the deterministic way to force the
post-execution health check to fail and trigger automatic rollback, without needing a real flaky
service.

## Runbook YAML format

```yaml
slug: restart-checkout-service     # lowercase kebab-case, 3-100 chars
version: 1                         # a (slug, version) pair is immutable once registered
title: Restart checkout-api after elevated error rate
riskClassification: MEDIUM         # LOW | MEDIUM | HIGH
steps:
  - name: restart-deployment
    adapter: KUBERNETES_RESTART    # must be one of RemediationActionType's closed set
    parameters:
      service: checkout-api
      environment: staging
    timeoutSeconds: 60             # 1-300, default 30
    maxRetries: 1                  # 0-5, default 0
    backoffInitialMillis: 1000     # 100-60000, default 500
    backoffMultiplier: 2.0         # 1.0-10.0, default 2.0
  - name: verify-health
    adapter: HEALTH_CHECK
    parameters:
      service: checkout-api
      environment: staging
rollbackSteps:                     # optional; run automatically on forward-step or health failure
  - name: rollback-deployment
    adapter: KUBERNETES_ROLLBACK
    parameters:
      service: checkout-api
      environment: staging
```

`RunbookYamlParser` uses SnakeYAML's `SafeConstructor` exclusively (plain maps/lists/scalars
only — a runbook document can never construct an arbitrary Java type) and rejects: unknown
top-level or step fields left unvalidated, an unrecognized `adapter` value, a missing required
field, and any out-of-range timeout/retry/backoff value. Three example runbooks ship under
`services/incident-service/src/main/resources/remediation-runbooks/` (one per risk tier) and are
registered automatically at startup by `RemediationRunbookService`.

## Policy engine

`PolicyEngine` evaluates an ordered chain of `PolicyRule`s; the first to return a decision wins,
and the final rule (`RiskClassificationPolicyRule`) always returns one, so a request can never
fall through undecided — this is the deny-by-default guarantee, verified directly in
`PolicyEngineTest`.

| Order | Rule | Denies when |
| --- | --- | --- |
| 1 | `AuthorizationPolicyRule` | Actor holds neither RESPONDER nor ADMIN |
| 2 | `RecentFailurePolicyRule` | The runbook has failed/rolled-back at least `circuitBreaker.failureThreshold` times in the last hour |
| 3 | `BlastRadiusPolicyRule` | Computed affected-resource count exceeds `blastRadius.maxResourceCount` |
| 4 | `MaintenanceWindowPolicyRule` | HIGH risk + a restricted environment (e.g. `production`) + no active maintenance window |
| 5 | `RiskClassificationPolicyRule` (terminal) | Never denies — maps risk tier (+ restricted-environment escalation) to `ALLOW` (0 approvals) or `REQUIRE_APPROVAL` (1 or 2) |

All thresholds are `sentinelops.remediation.*` configuration (`application.yml`), not hardcoded,
and every decision carries a human-readable `reason` and a `policyVersion` for audit purposes.

## State machine

```
PROPOSED ──► APPROVED ──► SCHEDULED ──► RUNNING ──► SUCCEEDED ──► ROLLED_BACK
   │             │             │           │            │
   ▼             ▼             ▼           ▼            ▼
 DENIED      CANCELLED     CANCELLED   CANCELLED      (terminal)
   │                                       │
   ▼                                       ▼
CANCELLED                               FAILED ──► ROLLED_BACK
```

Enforced entirely by `RemediationExecutionStateMachine` (a fixed `EnumMap` of allowed
transitions) and re-checked by every guarded repository update (`UPDATE ... WHERE status =
'<expected>'`), so a stale in-memory view can never silently clobber a transition made by another
scheduler instance or console action.

## Concurrency, locking, and idempotency

- **Idempotency:** `remediation_executions.idempotency_key` is `UNIQUE`; a retried `propose()`
  call with the same key returns the existing row rather than creating a second execution.
- **Distributed locking:** `RemediationLockRepository` acquires a simple DB-row lock
  (`INSERT ... ON CONFLICT DO NOTHING`, `resource_key` = `runbook:<id>|environment:<env>`) before
  the scheduler claims an execution, and releases it in a `finally` block. A stale lock past its
  lease expiry is cleared before the next acquire attempt.
- **Concurrency limit:** `sentinelops.remediation.max-concurrent-executions` bounds how many
  executions the scheduler processes per poll (`scheduler-batch-size`); the lock additionally
  prevents two conflicting remediations against the same runbook+environment from running at once.
- **Circuit breakers:** one `SimpleCircuitBreaker` per `RemediationActionType`
  (`AdapterCircuitBreakerRegistry`) — repeated failures of one action type open its breaker
  independent of every other adapter type.

## Approval requirements

- LOW risk in an unrestricted environment: auto-approved (`ALLOW`), no human review.
- LOW risk in a restricted environment, or MEDIUM risk anywhere: `REQUIRE_APPROVAL`, 1 (or 2 in a
  restricted environment) distinct approver(s).
- HIGH risk: always 2 distinct approvers, and only inside a configured maintenance window when the
  target environment is restricted.
- An actor can never approve their own request (`RemediationExecutionService.approve` checks
  `requestedBy` against the approver before recording anything).
- Required-approval counts are always enforced by counting distinct rows in
  `remediation_approvals` (`UNIQUE(execution_id, approver)`), never by trusting a client-supplied
  count.

## Cancellation and emergency stop

- **Cancel** (`POST /api/v1/remediations/{id}/cancel`): immediate state transition to `CANCELLED`
  while `PROPOSED`/`APPROVED`/`SCHEDULED`; while `RUNNING`, sets `cancel_requested`, which the
  scheduler checks between steps (cooperative — a step already in flight still finishes).
- **Emergency stop** (`POST /api/v1/remediations/{id}/emergency-stop`, administrator only): sets
  `emergency_stop`, checked the same way, for a faster/harder halt signal distinct from a normal
  cancellation.

## Observability

- **Audit:** every proposal, policy decision, approval, rejection, cancellation, emergency stop,
  success, failure, and rollback is recorded through the same `AuditRecorder` every other phase
  uses — one call site, one immutable trail, correlated by `correlationId`.
- **Metrics/tracing:** the scheduler wraps each execution in a `Spans.inSpan("remediation.execution.run", ...)` 
  child span, so a remediation execution's trace correlates with the incident/alert/MCP-proposal
  spans that led to it.

## Operator console

`/remediations` (React) lists recent executions with status, policy decision/reason, dry-run flag,
and (for RESPONDER/ADMIN) approve/reject/cancel controls plus an admin-only emergency-stop button;
"View steps" shows step-level status/output/error including any rollback steps; "Propose
remediation" lets a RESPONDER/ADMIN pick an active runbook and optionally mark the request
dry-run.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| GET | `/api/v1/runbooks` | List active runbooks |
| GET | `/api/v1/runbooks/{slug}` | One runbook's full YAML (for preview) |
| GET | `/api/v1/remediations` | Recent executions |
| GET | `/api/v1/incidents/{incidentId}/remediations` | Executions for one incident |
| GET | `/api/v1/remediations/{id}` | One execution |
| GET | `/api/v1/remediations/{id}/steps` | Step-level detail, including rollback steps |
| POST | `/api/v1/remediations` | Propose (or dry-run) an execution |
| POST | `/api/v1/remediations/{id}/approve` | Record an approval; schedules once the threshold is met |
| POST | `/api/v1/remediations/{id}/reject` | Deny a pending execution |
| POST | `/api/v1/remediations/{id}/cancel` | Cancel (cooperative while running) |
| POST | `/api/v1/remediations/{id}/emergency-stop` | Administrator-only hard stop |

## Threat model

| Threat | Mitigation |
| --- | --- |
| Arbitrary shell command execution | `DiagnosticCommandAdapter` uses a fixed-argv `ProcessBuilder`, never shell interpolation, against an explicit allowlist |
| Unbounded blast radius | `BlastRadiusPolicyRule` denies once the computed affected-resource count exceeds a configured limit |
| Privilege escalation via proposal | `AuthorizationPolicyRule` denies non-RESPONDER/ADMIN actors before any other rule runs |
| Self-approval | Rejected server-side in `RemediationExecutionService.approve` regardless of client behavior |
| Replay of an old approval/execution request | `idempotency_key` UNIQUE constraint; guarded status transitions (`WHERE status = '<expected>'`) |
| Parameter tampering between plan and execute | Every adapter re-validates parameters identically in both `plan()` and `execute()` — there is no separate "trusted" path |
| Runaway repeated failures | Both a policy-level `RecentFailurePolicyRule` and a per-adapter-type circuit breaker |
| Conflicting concurrent remediations against the same target | `RemediationLockRepository` distributed lock keyed by runbook+environment |
| Denial by default | `PolicyEngine`'s terminal rule always returns a decision; nothing "falls through" to an implicit allow |

## Local demo

```bash
docker compose --profile app up -d   # requires Docker (see docs/development/getting-started.md)
infrastructure/docker/scripts/remediation-demo.sh
```

Exercises: auto-approved low-risk execution → completion; medium-risk execution → distinct-actor
approval → completion; and the fault-injected unhealthy-target scenario → automatic rollback.

## Known limitations

- The scheduler runs a claimed execution to completion within one poll tick rather than across
  many short transactions (unlike `OutboxPublisher`'s claim/send/finalize split) — acceptable here
  because every adapter call is fast and local/simulated, never a real long-running external call;
  documented rather than hidden.
- No dedicated remediation health/readiness endpoint beyond the existing service-wide one.
- The maintenance window is a single fixed weekly schedule (`sentinelops.remediation.maintenance-window`),
  not a calendar of ad hoc windows.
- `remediation-demo.sh` and the full adversarial/security test matrix require Docker, which is not
  available in this development sandbox; syntax-checked and reviewed but not executed end-to-end
  here (see the phase completion report for the exact command and blocker).
