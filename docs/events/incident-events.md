# Incident Service Events

Status: Foundation. Describes the events the incident service consumes and publishes. All
events use the [envelope contract](event-envelope.md).

## Consumed

### `telemetry.anomaly.v1`

Published by a future detection engine (see `docs/architecture/system-overview.md`). Consuming
this event idempotently creates an incident — see
[ADR 0008](../decisions/0008-at-least-once-idempotent-consumers.md). The envelope's `eventId`
is stored as the created incident's `source_event_id`; redelivery of the same `eventId` never
creates a second incident.

Payload:

```json
{
  "title": "Checkout API returning elevated 5xx rate",
  "description": "p99 latency and 5xx rate on checkout-api exceeded SLO thresholds.",
  "severity": "SEV2",
  "affectedService": "checkout-api",
  "detectedAt": "2026-09-12T18:03:00Z"
}
```

`severity` must be one of `SEV1`, `SEV2`, `SEV3`, `SEV4`.

Dead-letter topic: `telemetry.anomaly.v1.dlq` — an anomaly event that cannot be processed after
the configured number of retries (see `sentinelops.incident-service.consumer.max-retries`) is
published here instead of being retried forever.

### `incident.evidence.correlated.v1`

Published by the telemetry-correlation service (Phase 5) for every piece of evidence a
correlation run selects for a detected incident — see
[docs/events/telemetry-correlation-events.md](telemetry-correlation-events.md) for the full
payload contract. Consumed idempotently (keyed by the envelope's `eventId`, tracked in this
service's own `processed_events` table) to append evidence to the named incident via
`IncidentCommandService.addEvidenceFromCorrelation`, attributed to actor type `EVENT_CONSUMER`
and actor ID `telemetry-correlation-service` (as opposed to operator-recorded evidence, which is
attributed to `LOCAL_USER`/`local-operator`). Duplicate delivery never creates duplicate
evidence; a `correlationScore` reflects rule-based proximity/connection to the incident, never a
confirmed root cause, and this evidence never overwrites anything an operator already recorded.

If the named incident does not exist, evidence recording fails and the event is retried with
bounded exponential backoff, then routed to `incident.evidence.correlated.v1.dlq` — see
`EvidenceCorrelatedListener`.

## Published

Both published events are written through the transactional outbox
([ADR 0007](../decisions/0007-transactional-outbox-pattern.md)) and are therefore
at-least-once, not exactly-once — consumers must be idempotent.

### `incident.detected.v1`

Published whenever a new incident is created, whether from the REST API or from a consumed
anomaly event.

Payload:

```json
{
  "incidentId": "1e6e0b9a-8e0a-4a2e-9c1a-2a6f6a1a3fa8",
  "incidentNumber": "INC-2026-000042",
  "title": "Checkout API returning elevated 5xx rate",
  "severity": "SEV2",
  "affectedService": "checkout-api",
  "source": "manual-report",
  "detectedAt": "2026-09-12T18:03:00Z"
}
```

Dead-letter topic: `incident.detected.v1.dlq` (provisioned for future consumers of this topic;
the incident service itself does not consume it).

### `audit.event.v1`

Published for every audited action (incident creation, status transition, evidence recording).
Mirrors an `audit.audit_events` row.

Payload:

```json
{
  "auditEventId": "9c1a2a6f-6a1a-3fa8-1e6e-0b9a8e0a4a2e",
  "incidentId": "1e6e0b9a-8e0a-4a2e-9c1a-2a6f6a1a3fa8",
  "action": "INCIDENT_CREATED",
  "actorType": "LOCAL_USER",
  "actorId": "local-operator"
}
```

`action` values used in this phase: `INCIDENT_CREATED`, `INCIDENT_TRANSITIONED`,
`EVIDENCE_RECORDED`. `actorType` is one of `SYSTEM`, `LOCAL_USER`, `EVENT_CONSUMER`.

No dead-letter topic: audit events are append-only records of something that already happened
and are not retried against a failure topic.
