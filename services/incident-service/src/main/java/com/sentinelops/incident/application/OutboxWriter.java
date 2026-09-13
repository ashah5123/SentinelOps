package com.sentinelops.incident.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.config.IncidentServiceProperties;
import com.sentinelops.incident.domain.OutboxEvent;
import com.sentinelops.incident.events.EventEnvelope;
import com.sentinelops.incident.infrastructure.persistence.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes a transactional outbox row for an event. Callers are expected to invoke this from within
 * the same database transaction as the domain change the event describes — this class does not open
 * or manage its own transaction, so it participates in the caller's.
 */
@Component
public class OutboxWriter {

  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;
  private final String producerName;

  public OutboxWriter(
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper,
      IncidentServiceProperties properties) {
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
    this.producerName = properties.producerName();
  }

  /**
   * Appends a pending outbox row. The topic name doubles as the event type, matching this service's
   * event-naming convention (e.g. topic {@code incident.detected.v1} carries events of type {@code
   * incident.detected.v1}) — see {@code docs/events/event-envelope.md}.
   */
  public <T> void append(
      String aggregateType,
      UUID aggregateId,
      String topic,
      int schemaVersion,
      T payload,
      String correlationId) {
    EventEnvelope<T> envelope =
        EventEnvelope.of(topic, schemaVersion, Instant.now(), correlationId, producerName, payload);
    String serialized;
    try {
      serialized = objectMapper.writeValueAsString(envelope);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Failed to serialize outbox event payload for " + topic, e);
    }
    outboxEventRepository.save(
        OutboxEvent.pending(
            aggregateType, aggregateId, topic, topic, schemaVersion, serialized, correlationId));
  }
}
