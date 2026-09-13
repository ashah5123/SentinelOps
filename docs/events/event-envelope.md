# Event Envelope Contract

Status: Foundation. This document describes the envelope every SentinelOps service uses to
publish and consume events over Redpanda's Kafka-compatible API. It is the canonical reference
for the shape defined in code at
`services/incident-service/src/main/java/com/sentinelops/incident/events/EventEnvelope.java`.

## Delivery semantics

**At-least-once, not exactly-once.** A message may be delivered and processed more than once.
Every consumer must be idempotent with respect to redelivery of the same `eventId`. See
[ADR 0007](../decisions/0007-transactional-outbox-pattern.md) and
[ADR 0008](../decisions/0008-at-least-once-idempotent-consumers.md) for the full rationale.

## Envelope shape

Every event published to a SentinelOps topic is a JSON object with this shape:

```json
{
  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "eventType": "incident.detected.v1",
  "schemaVersion": 1,
  "occurredAt": "2026-09-12T18:04:00Z",
  "correlationId": "b6e1f6b0-9f1a-4a2e-8e2a-1c9a0e6f6a1a",
  "producer": "incident-service",
  "payload": { }
}
```

| Field | Type | Description |
|---|---|---|
| `eventId` | UUID | Globally unique ID for this event instance. Used by consumers as the idempotency key. |
| `eventType` | string | The dot-versioned event type, matching the Kafka topic name (e.g. `incident.detected.v1`). |
| `schemaVersion` | integer | The payload schema version. Incremented only on breaking payload changes. |
| `occurredAt` | ISO-8601 UTC timestamp | When the underlying fact occurred — not when the event was published. |
| `correlationId` | string | Propagated from the originating HTTP request (or upstream event) across logs, database records, and downstream events. |
| `producer` | string | Logical name of the service that produced the event. |
| `payload` | object | Event-type-specific body. See `docs/events/incident-events.md` for the incident-service's payloads. |

## Serialization

JSON serialization is configured explicitly (ISO-8601 timestamps, not epoch numbers; unknown
fields on read are ignored to allow additive, backward-compatible payload changes). Messages
carry no Java-class type-header metadata — the envelope's `eventType` and `schemaVersion`
fields are the only type information a consumer needs.

## Adding a new event type

1. Add the topic name and schema version constants to `EventTypes`.
2. Define a payload record next to the existing ones in the `events` package.
3. Document the payload shape in `docs/events/incident-events.md` (or a new file, for a future
   service's own events).
4. If the event can fail permanently on the consumer side, provision a corresponding
   `<topic>.dlq` topic (see `infrastructure/docker/redpanda/init-topics.sh`).
