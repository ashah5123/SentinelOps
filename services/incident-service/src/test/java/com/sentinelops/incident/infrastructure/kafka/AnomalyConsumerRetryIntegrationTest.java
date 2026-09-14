package com.sentinelops.incident.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.events.EventEnvelope;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.events.TelemetryAnomalyPayload;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.support.AbstractIntegrationTest;
import java.time.Instant;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Verifies a transient failure during anomaly processing is retried automatically (Spring Kafka's
 * {@code DefaultErrorHandler} re-invoking the listener per its configured backoff — see {@code
 * KafkaConfig}) and eventually succeeds, rather than being dead-lettered on the first failure.
 * {@link AuditRecorder} is spied to fail exactly once, standing in for any transient infrastructure
 * error (e.g. a momentary database hiccup) that a real deployment might see.
 */
class AnomalyConsumerRetryIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ObjectMapper objectMapper;
  @Autowired private IncidentRepository incidentRepository;
  @MockitoSpyBean private AuditRecorder auditRecorder;

  private KafkaProducer<String, String> producer() {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new KafkaProducer<>(props);
  }

  @Test
  void aTransientFailureIsRetriedAndTheAnomalyEventuallyCreatesExactlyOneIncident()
      throws Exception {
    doThrow(new RuntimeException("simulated transient failure"))
        .doCallRealMethod()
        .when(auditRecorder)
        .record(any(), eq("INCIDENT_CREATED"), any(), any(), any(), any());

    UUID eventId = UUID.randomUUID();
    EventEnvelope<TelemetryAnomalyPayload> envelope =
        new EventEnvelope<>(
            eventId,
            EventTypes.TELEMETRY_ANOMALY_V1,
            1,
            Instant.parse("2026-09-12T18:00:00Z"),
            "corr-retry-1",
            "test-detector",
            new TelemetryAnomalyPayload(
                "Transient-failure retry test",
                "desc",
                "SEV3",
                "checkout-api",
                Instant.parse("2026-09-12T18:00:00Z")));

    try (KafkaProducer<String, String> producer = producer()) {
      producer
          .send(
              new ProducerRecord<>(
                  EventTypes.TELEMETRY_ANOMALY_V1,
                  eventId.toString(),
                  objectMapper.writeValueAsString(envelope)))
          .get();
    }

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () ->
                assertThat(incidentRepository.findBySourceEventId(eventId.toString())).isPresent());

    long matchingIncidents =
        incidentRepository.findAll().stream()
            .filter(i -> eventId.toString().equals(i.getSourceEventId()))
            .count();
    assertThat(matchingIncidents).isEqualTo(1);
  }
}
