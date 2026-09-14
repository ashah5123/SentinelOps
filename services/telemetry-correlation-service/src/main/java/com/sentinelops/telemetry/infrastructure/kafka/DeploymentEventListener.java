package com.sentinelops.telemetry.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.sentinelops.telemetry.application.DeploymentService;
import com.sentinelops.telemetry.events.DeploymentChangedPayload;
import com.sentinelops.telemetry.events.EventEnvelope;
import com.sentinelops.telemetry.events.EventTypes;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code deployment.changed.v1}. The listener container's {@code AckMode.RECORD} (see
 * {@code KafkaConfig}) commits the offset only after this method returns normally — i.e. only after
 * {@link DeploymentService#recordDeployment} has committed its database transaction.
 */
@Component
public class DeploymentEventListener {

  private final DeploymentService deploymentService;
  private final PayloadValidation payloadValidation;
  private final ObjectMapper objectMapper;

  public DeploymentEventListener(
      DeploymentService deploymentService,
      PayloadValidation payloadValidation,
      ObjectMapper objectMapper) {
    this.deploymentService = deploymentService;
    this.payloadValidation = payloadValidation;
    this.objectMapper = objectMapper;
  }

  @KafkaListener(
      topics = EventTypes.DEPLOYMENT_CHANGED_V1,
      groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(String rawEnvelope) throws Exception {
    EventEnvelope<DeploymentChangedPayload> envelope =
        objectMapper.readValue(
            rawEnvelope,
            TypeFactory.defaultInstance()
                .constructParametricType(EventEnvelope.class, DeploymentChangedPayload.class));
    payloadValidation.validate(envelope.payload());
    deploymentService.recordDeployment(
        envelope.payload(), envelope.eventId().toString(), envelope.correlationId());
  }
}
