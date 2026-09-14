package com.sentinelops.telemetry.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sentinelops.telemetry.application.CorrelationEngine;
import com.sentinelops.telemetry.events.EventEnvelope;
import com.sentinelops.telemetry.events.EventTypes;
import com.sentinelops.telemetry.events.IncidentDetectedPayload;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code incident.detected.v1} (published by the incident service — see {@code
 * docs/events/incident-events.md}) to trigger a correlation run. Idempotent on the envelope's
 * {@code eventId} — see {@link CorrelationEngine#correlate}.
 */
@Component
public class IncidentDetectedListener {

  private final CorrelationEngine correlationEngine;
  private final ObjectMapper objectMapper;

  public IncidentDetectedListener(CorrelationEngine correlationEngine, ObjectMapper objectMapper) {
    this.correlationEngine = correlationEngine;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = EventTypes.INCIDENT_DETECTED_V1,
      groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(String rawEnvelope) throws Exception {
    EventEnvelope<IncidentDetectedPayload> envelope =
        objectMapper.readValue(
            rawEnvelope,
            TypeFactory.defaultInstance()
                .constructParametricType(EventEnvelope.class, IncidentDetectedPayload.class));
    correlationEngine.correlate(
        envelope.payload(), envelope.eventId().toString(), envelope.correlationId());
  }
}
