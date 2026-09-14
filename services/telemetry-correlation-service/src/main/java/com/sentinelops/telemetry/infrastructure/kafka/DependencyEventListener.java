package com.sentinelops.telemetry.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sentinelops.telemetry.application.DependencyGraphService;
import com.sentinelops.telemetry.events.EventEnvelope;
import com.sentinelops.telemetry.events.EventTypes;
import com.sentinelops.telemetry.events.ServiceDependencyChangedPayload;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code service.dependency.changed.v1}. See {@link DependencyGraphService} for
 * idempotency, validation, and cycle-handling rules.
 */
@Component
public class DependencyEventListener {

  private final DependencyGraphService dependencyGraphService;
  private final PayloadValidation payloadValidation;
  private final ObjectMapper objectMapper;

  public DependencyEventListener(
      DependencyGraphService dependencyGraphService,
      PayloadValidation payloadValidation,
      ObjectMapper objectMapper) {
    this.dependencyGraphService = dependencyGraphService;
    this.payloadValidation = payloadValidation;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = EventTypes.SERVICE_DEPENDENCY_CHANGED_V1,
      groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(String rawEnvelope) throws Exception {
    EventEnvelope<ServiceDependencyChangedPayload> envelope =
        objectMapper.readValue(
            rawEnvelope,
            TypeFactory.defaultInstance()
                .constructParametricType(
                    EventEnvelope.class, ServiceDependencyChangedPayload.class));
    payloadValidation.validate(envelope.payload());
    dependencyGraphService.applyChange(
        envelope.payload(), envelope.eventId().toString(), envelope.correlationId());
  }
}
