# Telemetry Correlation Service Events

Status: Phase 5. Describes the events the telemetry-correlation service consumes and publishes.
All events use the [envelope contract](event-envelope.md) — see
`docs/events/schemas/event-envelope.schema.json` for its JSON Schema. Every payload below has a
matching JSON Schema file under `docs/events/schemas/`, and every schema is exercised by a
contract test (`EventContractTest` in the telemetry-correlation-service test suite) that
serializes the real Java payload record and validates it against the schema — a field added,
renamed, or retyped in code without updating the schema fails that test.

## Compatibility policy

Payload schemas are additive-compatible: new optional fields may be added without incrementing
`schemaVersion`, because unknown fields are ignored on read (see `JacksonConfig` in both
services). Removing a field, changing a field's type, or changing a required field to a
different meaning requires incrementing `schemaVersion` and is a breaking change — consumers
pinned to the old version must be updated in lockstep, per the envelope contract.

## Consumed

### `deployment.changed.v1`

Published whenever a deployment starts, succeeds, fails, or is rolled back for a monitored
service.

Payload:

```json
{
  "deploymentId": "deploy-2026-01-01-001",
  "serviceName": "incident-service",
  "version": "1.4.2",
  "environment": "local",
  "status": "SUCCEEDED",
  "startedAt": "2026-01-01T00:00:00Z",
  "completedAt": "2026-01-01T00:01:30Z",
  "source": "github-actions",
  "rollbackOfDeploymentId": null
}
```

| Field | Required | Description |
|---|---|---|
| `deploymentId` | yes | Stable identifier for this deployment, unique per service (e.g. a CI run ID) — distinct from the envelope's `eventId`. Max 128 chars. |
| `serviceName` | yes | Bounded, safe format: `^[a-z0-9]([a-z0-9-]{0,148}[a-z0-9])?$`, max 150 chars. |
| `version` | yes | Deployed version or commit SHA. Max 128 chars. |
| `environment` | yes | Target environment. Max 50 chars. |
| `status` | yes | One of `STARTED`, `SUCCEEDED`, `FAILED`, `ROLLED_BACK`. |
| `startedAt` | yes | UTC ISO-8601 timestamp. |
| `completedAt` | no | UTC ISO-8601 timestamp; absent/`null` while `STARTED`. |
| `source` | yes | Producing system, e.g. `github-actions`, `manual`. Max 100 chars. |
| `rollbackOfDeploymentId` | no | The `deploymentId` this rolls back, if any. Max 128 chars. |

**Example invalid event** (fails schema validation — `serviceName` violates the safe-format
pattern, and `status` is not one of the allowed values):

```json
{ "deploymentId": "d1", "serviceName": "Incident Service!", "version": "1.0",
  "environment": "local", "status": "IN_PROGRESS", "startedAt": "2026-01-01T00:00:00Z",
  "source": "ci" }
```

**Delivery/idempotency**: at-least-once; idempotent on the envelope's `eventId` (tracked in
`telemetry.deployments.source_event_id`, unique). Schema-invalid or unparseable events are
retried with bounded exponential backoff, then routed to `deployment.changed.v1.dlq`.

### `service.dependency.changed.v1`

Represents an added, updated, or removed directed service-dependency edge.

Payload:

```json
{
  "sourceService": "incident-service",
  "targetService": "postgres",
  "dependencyType": "DATABASE",
  "environment": "local",
  "operation": "ADDED",
  "effectiveAt": "2026-01-01T00:00:00Z"
}
```

| Field | Required | Description |
|---|---|---|
| `sourceService` | yes | The dependent service. Same safe-format pattern as `deployment.changed.v1`'s `serviceName`. |
| `targetService` | yes | The depended-upon service. Same format. Must differ from `sourceService` — a service cannot depend on itself (rejected, not silently accepted). |
| `dependencyType` | yes | E.g. `HTTP`, `KAFKA`, `DATABASE`. Max 50 chars. |
| `environment` | yes | Max 50 chars. |
| `operation` | yes | One of `ADDED`, `UPDATED`, `REMOVED`. |
| `effectiveAt` | yes | UTC ISO-8601 timestamp the change took effect. |

**Example invalid event** (fails schema validation — `operation` is not one of the allowed
enum values):

```json
{ "sourceService": "a", "targetService": "b", "dependencyType": "HTTP", "environment": "local",
  "operation": "MODIFIED", "effectiveAt": "2026-01-01T00:00:00Z" }
```

**Delivery/idempotency**: at-least-once; idempotent on the envelope's `eventId` (tracked in
`telemetry.service_dependency_history.source_event_id`, unique). A `REMOVED` event for an edge
that does not currently exist is a deterministic no-op on the graph (the desired end state is
already true) but is still recorded in history. A dependency **cycle** (A depends on B, B
depends on A) is valid and accepted — only self-dependency is rejected. Schema-invalid or
unparseable events are retried, then routed to `service.dependency.changed.v1.dlq`.

## Published

### `incident.evidence.correlated.v1`

Published through this service's transactional outbox (ADR 0007) for every piece of evidence a
correlation run selects for a detected incident. Consumed by the incident service (see
`docs/events/incident-events.md`) to append evidence to the corresponding incident.

Payload:

```json
{
  "incidentId": "1e6e0b9a-8e0a-4a2e-9c1a-2a6f6a1a3fa8",
  "evidenceId": "9c1a2a6f-6a1a-3fa8-1e6e-0b9a8e0a4a2e",
  "evidenceType": "TRACE",
  "summary": "trace POST /api/v1/incidents",
  "sourceService": "incident-service",
  "observedAt": "2026-01-01T00:03:05Z",
  "sourceReference": "tempo:trace/abc123",
  "correlationScore": 72.5,
  "scoreExplanation": "affected_service_match(+40); time_proximity(+18.5); error_or_failed_status(+15)",
  "traceId": "abc123",
  "deploymentId": null
}
```

| Field | Required | Description |
|---|---|---|
| `incidentId` | yes | The incident this evidence belongs to. |
| `evidenceId` | yes | The telemetry-correlation service's own `Evidence.id`. |
| `evidenceType` | yes | One of `METRIC`, `LOG`, `TRACE`, `DEPLOYMENT`, `DEPENDENCY`. |
| `summary` | yes | Sanitized, bounded (max 500 chars) human-readable summary. |
| `sourceService` | yes | The service this evidence was observed on. |
| `observedAt` | yes | UTC ISO-8601 timestamp the evidence was observed. |
| `sourceReference` | yes | Opaque reference back to the source (a query, a trace URL, etc.), max 500 chars. |
| `correlationScore` | yes | Non-negative rule-based score. **Not a probability or a confidence in causation.** |
| `scoreExplanation` | yes | Human-readable list of which rules contributed, max 1000 chars. |
| `traceId` | no | Present when the evidence has an associated trace ID. |
| `deploymentId` | no | Present when the evidence is a deployment record. |

**Example invalid event** (fails schema validation — `correlationScore` is negative, which the
schema's `minimum: 0` rejects):

```json
{ "incidentId": "...", "evidenceId": "...", "evidenceType": "METRIC", "summary": "s",
  "sourceService": "svc", "observedAt": "2026-01-01T00:00:00Z", "sourceReference": "ref",
  "correlationScore": -5, "scoreExplanation": "" }
```

**Delivery/idempotency**: at-least-once via transactional outbox; the incident service is
idempotent on the envelope's `eventId` (tracked in its own `processed_events` table) —
redelivery never creates duplicate incident evidence. If the named incident does not exist, the
incident service's consumption fails and the event is retried, then routed to
`incident.evidence.correlated.v1.dlq`, per the incident service's `EvidenceCorrelatedListener`
documentation.

## Also consumed (not owned by this service)

### `incident.detected.v1`

Consumed to trigger a correlation run — see `docs/events/incident-events.md` for the
authoritative payload published by the incident service. Only `incidentId`, `affectedService`,
`severity`, and `detectedAt` are used. Idempotent on the envelope's `eventId` (tracked in
`telemetry.correlation_results.source_event_id`, unique) — redelivery never runs correlation
twice for the same incident-detected event.
