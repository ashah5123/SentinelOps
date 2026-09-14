package com.sentinelops.telemetry.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.OutboxEvent;
import com.sentinelops.telemetry.events.EventEnvelope;
import com.sentinelops.telemetry.infrastructure.persistence.OutboxEventRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Writes a transactional outbox row for an event. Callers are expected to invoke this from within
 * the same database transaction as the domain change the event describes — mirrors the incident
 * service's own {@code OutboxWriter} (see ADR 0007).
 */
@Component
public class OutboxWriter {

  private final OutboxEventRepository outboxEventRepository;
  private final ObjectMapper objectMapper;
  private final String producerName;

  public OutboxWriter(
      OutboxEventRepository outboxEventRepository,
      ObjectMapper objectMapper,
      TelemetryCorrelationProperties properties) {
    this.outboxEventRepository = outboxEventRepository;
    this.objectMapper = objectMapper;
    this.producerName = properties.producerName();
  }

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
