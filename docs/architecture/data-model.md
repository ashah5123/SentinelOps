# Data model

High-level entity relationships across the four PostgreSQL schemas (`incidents`, `audit`,
`telemetry`, `runbooks`) this platform uses. For exact column definitions, see the Flyway
migrations under `services/*/src/main/resources/db/migration/` — this document is the map, not
the source of truth.

```mermaid
erDiagram
    INCIDENTS ||--o{ AUDIT_EVENTS : "generates"
    INCIDENTS ||--o{ ALERT_EVENTS : "correlated from"
    INCIDENTS ||--o{ AI_SUGGESTIONS : "triaged by"
    INCIDENTS ||--o{ AGENT_PROPOSALS : "proposed against"
    INCIDENTS ||--o{ NOTIFICATIONS : "notifies via"
    INCIDENTS ||--o{ REMEDIATION_EXECUTIONS : "remediated via"
    AGENT_PROPOSALS ||--o| REMEDIATION_EXECUTIONS : "may trigger"
    REMEDIATION_RUNBOOKS ||--o{ REMEDIATION_EXECUTIONS : "executed as"
    REMEDIATION_EXECUTIONS ||--o{ REMEDIATION_STEPS : "runs"
    REMEDIATION_EXECUTIONS ||--o{ REMEDIATION_APPROVALS : "approved by"
    OUTBOX_EVENTS }o--|| INCIDENTS : "published for"

    INCIDENTS {
        uuid id PK
        string incident_number UK
        string severity
        string status
        string correlation_id
        bigint version
    }
    ALERT_EVENTS {
        uuid id PK
        string dedup_key UK
        string fingerprint
        uuid incident_id FK
    }
    AGENT_PROPOSALS {
        uuid id PK
        uuid incident_id FK
        string action_type
        string status
        string idempotency_key UK
    }
    REMEDIATION_RUNBOOKS {
        uuid id PK
        string slug
        int version
        boolean is_active
    }
    REMEDIATION_EXECUTIONS {
        uuid id PK
        uuid runbook_id FK
        uuid incident_id FK
        uuid proposal_id FK
        string status
        string idempotency_key UK
    }
    REMEDIATION_STEPS {
        uuid id PK
        uuid execution_id FK
        int step_index
        string status
    }
    AUDIT_EVENTS {
        uuid id PK
        uuid incident_id FK
        string action
        string actor_id
    }
    OUTBOX_EVENTS {
        uuid id PK
        string aggregate_type
        string event_type
        string status
    }
```

## Schema ownership

| Schema | Owning service | Contents |
| --- | --- | --- |
| `incidents` | incident-service | Incidents, alerts, notifications, AI suggestions, agent proposals, remediation runbooks/executions/steps/approvals, the transactional outbox, simulated deployment/queue/cache/flag state |
| `audit` | incident-service | Immutable audit events |
| `telemetry` | telemetry-correlation-service | Deployment events, dependency-change events, correlation results |
| `runbooks` | incident-service | pgvector-backed runbook document chunks and embeddings (AI-triage retrieval) — distinct from `remediation_runbooks` in the `incidents` schema, which are executable YAML runbooks, not retrieval documents |

## Key invariants

- Every idempotency-sensitive table (`alert_events.dedup_key`, `agent_proposals.idempotency_key`,
  `remediation_executions.idempotency_key`) enforces a `UNIQUE` constraint — durable, retry-safe
  idempotency at the database level, never only in application logic.
- `incidents.version` is an optimistic-locking column checked on every command
  (`IncidentCommandService`) and re-verified by both the agent-approval and remediation-approval
  flows before executing an action against potentially stale state.
- `audit_events` has no `UPDATE`/`DELETE` path anywhere in the application — append-only by
  construction, not merely by convention (see `docs/operations/data-retention-and-audit-policy.md`).
