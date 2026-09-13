package com.sentinelops.incident.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sentinelops.incident.application.AnomalyIncidentProcessor;
import com.sentinelops.incident.events.EventEnvelope;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.events.TelemetryAnomalyPayload;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code telemetry.anomaly.v1}. The listener container's {@code AckMode.RECORD} (see
 * {@code KafkaConfig}) commits the offset only after this method returns normally, i.e. only after
 * {@link AnomalyIncidentProcessor#process} has committed its database transaction — the offset is
 * never advanced ahead of the corresponding write.
 */
@Component
public class AnomalyEventListener {

  private final AnomalyIncidentProcessor processor;
  private final ObjectMapper objectMapper;

  public AnomalyEventListener(AnomalyIncidentProcessor processor, ObjectMapper objectMapper) {
    this.processor = processor;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = EventTypes.TELEMETRY_ANOMALY_V1,
      groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(String rawEnvelope) throws Exception {
    EventEnvelope<TelemetryAnomalyPayload> envelope =
        objectMapper.readValue(
            rawEnvelope,
            TypeFactory.defaultInstance()
                .constructParametricType(EventEnvelope.class, TelemetryAnomalyPayload.class));
    processor.process(envelope, EventTypes.TELEMETRY_ANOMALY_V1);
  }
}
