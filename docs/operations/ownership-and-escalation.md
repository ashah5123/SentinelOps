# Operational ownership and escalation

SentinelOps is a single-author portfolio project (see `LICENSE`, `CONTRIBUTING.md`) — this
document describes the ownership *model* a team would adopt operating it, not a real
multi-person on-call rotation, since none exists for this repository today.

## Ownership model (as designed, for a team adopting this platform)

| Area | Owner (role, not a named individual) | Responsibilities |
| --- | --- | --- |
| Application (incident-service, telemetry-correlation-service) | Platform/backend team | Code changes, dependency upgrades, application-level incident response |
| Kubernetes/Helm/Terraform | Platform/infrastructure team | Cluster health, capacity, the deployment pipeline itself |
| Remediation runbooks and policy | SRE/on-call leads | Authoring and approving new runbooks (`docs/development/remediation.md`'s runbook-authoring guide), reviewing policy thresholds |
| Security (dependency/secret scanning, threat model) | Security team or a designated security owner | Triaging CI security-job findings, maintaining `docs/architecture/threat-model.md` |
| Data retention / audit policy | Compliance or platform lead | Reviewing `docs/operations/data-retention-and-audit-policy.md` against actual regulatory requirements for the deploying organization |

## Escalation path (as designed)

1. **On-call operator** — first responder, follows `docs/operations/incident-response.md`.
2. **Service owner** (the relevant row above) — engaged if the on-call operator's runbook does
   not resolve the issue within its documented expected timeframe.
3. **Platform lead** — engaged for anything requiring a production infrastructure change
   (Terraform apply, IAM change) outside a pre-approved runbook.

## What this repository does NOT claim

No named individuals, real pager/on-call tooling integration, or actual incident history are
represented here — this is the operating model this platform is *designed* to support (its RBAC
roles, audit trail, and remediation approval gates all exist specifically to make this kind of
ownership model enforceable), not evidence that such a team currently operates it.
