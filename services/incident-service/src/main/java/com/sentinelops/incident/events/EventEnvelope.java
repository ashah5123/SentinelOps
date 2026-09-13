package com.sentinelops.incident.events;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The versioned envelope wrapping every event SentinelOps services publish to or consume from
 * Redpanda's Kafka-compatible API.
 *
 * <p>See {@code docs/events/event-envelope.md} for the full contract documentation. The envelope is
 * intentionally broker-agnostic and independent of any specific event's payload shape; {@code
 * payload} carries the event-type-specific body as a nested JSON object.
 *
 * @param eventId globally unique identifier for this event instance.
 * @param eventType the dot-versioned event type, matching the Kafka topic name (e.g. {@code
 *     "incident.detected.v1"}).
 * @param schemaVersion the payload schema version, incremented on breaking payload changes.
 * @param occurredAt UTC instant the event's underlying fact occurred (not when it was published).
 * @param correlationId the correlation ID propagated across the HTTP request, database records,
 *     logs, and downstream events that led to this event.
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
