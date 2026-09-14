package com.sentinelops.telemetry.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The versioned envelope wrapping every event this service publishes to or consumes from Redpanda's
 * Kafka-compatible API. Field-for-field identical to the incident service's own {@code
 * EventEnvelope} (see {@code docs/events/event-envelope.md}) — deliberately re-declared here rather
 * than shared as compiled code, per ADR 0002 (polyglot services communicate over language-agnostic
 * contracts, not shared runtime libraries) and this phase's own ADR. The JSON Schema at {@code
 * docs/events/schemas/event-envelope.schema.json} is the single source of truth both services'
 * envelopes are contract-tested against.
 *
 * @param eventId globally unique identifier for this event instance; the idempotency key.
 * @param eventType the dot-versioned event type, matching the Kafka topic name.
 * @param schemaVersion the payload schema version, incremented on breaking payload changes.
 * @param occurredAt UTC instant the underlying fact occurred (not when it was published).
 * @param correlationId the correlation ID propagated across the request/event chain that led to
 *     this event.
 * @param producer the logical name of the service that produced this event.
 * @param payload the event-type-specific payload.
 * @param <T> the payload type.
 */
public record EventEnvelope<T>(
    @JsonProperty("eventId") UUID eventId,
    @JsonProperty("eventType") String eventType,
    @JsonProperty("schemaVersion") int schemaVersion,
    @JsonProperty("occurredAt") Instant occurredAt,
    @JsonProperty("correlationId") String correlationId,
    @JsonProperty("producer") String producer,
    @JsonProperty("payload") T payload) {

  @JsonCreator
  public EventEnvelope {
    // Canonical constructor retained for explicit Jackson deserialization wiring.
  }

  public static <T> EventEnvelope<T> of(
      String eventType,
      int schemaVersion,
      Instant occurredAt,
      String correlationId,
      String producer,
      T payload) {
    return new EventEnvelope<>(
        UUID.randomUUID(), eventType, schemaVersion, occurredAt, correlationId, producer, payload);
  }
}
