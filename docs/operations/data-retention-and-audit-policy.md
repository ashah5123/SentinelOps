# Data retention and audit-log policy

## Audit records

Every audit event (`audit.audit_events`, written by `AuditRecorder` — the single mutation-audit
entry point used across every phase of this platform) is **append-only and retained
indefinitely** by default. No code path in this repository deletes or truncates an audit record.
If a real deployment needs a bounded retention period (e.g. for storage-cost or compliance
reasons), that would be a new, explicit, reviewed migration adding a retention job — not a
silent behavior of the current system.

## Incident and telemetry data

| Data | Default retention | Mechanism |
| --- | --- | --- |
| Incidents, evidence, timeline | Indefinite | No automatic deletion exists |
| Kafka topic data | 7 days (`REDPANDA_TOPIC_RETENTION_MS`, `infrastructure/docker/redpanda/init-topics.sh`) | Broker-level retention, not application-level — durable state (incidents, audit) is in PostgreSQL, not Kafka, so this is safe |
| PostgreSQL backups | 14 days (dev default) / 30 days (production default) | `backup_retention_days` in `infrastructure/terraform/modules/rds-postgres` |
| S3 object versions (runbooks/artifacts/postmortems) | 90 days for noncurrent versions | `noncurrent_version_expiration_days` in `infrastructure/terraform/modules/object-storage` |
| Notification records | Indefinite (delivery status retained for troubleshooting) | No automatic deletion exists |
| Chaos-experiment / validation run artifacts | Local only, git-ignored | `infrastructure/docker/results/`, `infrastructure/docker/backups/` — never committed |

## Access to audit data

Reading audit records requires the same RBAC as everything else (`RolePermissionMatrixTest`
covers this) — VIEWER can read, only RESPONDER/ADMIN can act on what the audit trail describes.
Audit records themselves are never editable through any API this platform exposes.

## Personal data

This platform's domain model (incidents, alerts, evidence, audit) does not model end-customer
personal data — it models operational/infrastructure events (service names, alert payloads,
operator actor IDs). Actor IDs are operator identities (Keycloak subjects), not customer PII.
Real deployments should still review actual alert/evidence payload content for
organization-specific sensitive data before treating this as a complete data-classification
statement — this document describes the platform's own schema, not what an organization chooses
to put into free-text fields (incident description, evidence content).

## What this phase changed vs. what already existed

Nothing about existing retention behavior changed in this phase — this document is new
(consolidating and making explicit policy that was previously implicit in the schema/migration
defaults), not a policy change.
