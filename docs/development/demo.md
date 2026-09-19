# Portfolio demonstration

## Start

```bash
make demo
# or: infrastructure/docker/scripts/full-demo.sh
```

Starts the full local platform (app + observability Compose profiles), then walks through all
11 stages of the platform's workflow using deterministic seed data — no external production
system is required. Takes a few minutes; most of that time is container startup.

## Clean up

```bash
make demo-cleanup
# or: infrastructure/docker/scripts/full-demo-cleanup.sh
```

Stops every Compose profile and removes their volumes. Never touches the source tree or git
history. Safe to run even if the demo only partially started.

## Five-minute technical walkthrough script

Use this as narration while `make demo`'s output scrolls, or re-run pieces individually against
an already-running platform.

**0:00 – Ingestion (Steps 1-3).** "SentinelOps ingests alerts through two connectors — an
Alertmanager-compatible webhook and a generic HMAC-signed webhook. Watch: I send the same alert
twice — [`alert-demo.sh`'s duplicate-delivery check] — and it's deduplicated at the database
level via a unique constraint, not just at the application layer. Then a *related* alert for the
same service creates one correlated incident, not two."

**1:00 – AI triage (Step 4).** "Once an incident exists, an operator (or an automated policy)
can request an AI-generated triage suggestion. It retrieves relevant runbook passages via
pgvector similarity search and cites them directly — never a fabricated citation. If the AI
provider is slow, unavailable, or returns garbage, the incident is completely unaffected — triage
is additive, never load-bearing for the core workflow. [Point at the `ChaosInjectingAiProvider`
test if asked how that's actually verified, not just claimed.]"

**2:00 – Operator console (Step 5).** "Here's the incident, its evidence timeline, and the AI
suggestion, in the console. Everything here is read through the same REST API a curl request
would use — there's no separate, more-trusted internal path."

**2:30 – MCP and remediation proposal (Steps 6-7).** "An AI agent — Claude Code, Codex, anything
speaking MCP — can call `propose_action` against this incident. It can *never* execute a change
directly; every mutation goes through a second, human-approval stage. Separately, for
infrastructure-level fixes, there's a policy-controlled remediation engine: propose a runbook,
and a deterministic policy engine decides ALLOW / DENY / REQUIRE_APPROVAL based on risk, blast
radius, and environment — never a client-side decision."

**3:30 – Controlled execution and health validation (Steps 8-9).** "Once approved, the scheduler
runs the runbook step by step, with retries, a circuit breaker, and a health check before and
after. If that post-execution health check fails..."

**4:00 – Failure injection and rollback (Step 11).** "...like right now — I've forced the target
unhealthy — the engine automatically runs the runbook's rollback steps and lands the execution on
`ROLLED_BACK`, fully audited, never left in an ambiguous state."

**4:40 – Audit and observability (Step 10).** "Every one of those actions — the proposal, the
approval, every step transition, the rollback — is in an immutable audit trail. And the whole
thing is instrumented: Prometheus metrics, OpenTelemetry traces correlating the alert through to
the remediation execution, all visible in Grafana."

**5:00 – Close.** "That's alert-to-remediation, end to end, with a human approval gate and an
automatic safety net — reproducible with one command and zero external dependencies."
