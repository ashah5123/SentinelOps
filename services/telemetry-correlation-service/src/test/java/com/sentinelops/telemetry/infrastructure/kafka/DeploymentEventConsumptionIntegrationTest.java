package com.sentinelops.telemetry.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.telemetry.events.DeploymentChangedPayload;
import com.sentinelops.telemetry.events.EventEnvelope;
import com.sentinelops.telemetry.events.EventTypes;
import com.sentinelops.telemetry.infrastructure.persistence.DeploymentRepository;
import com.sentinelops.telemetry.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies {@code deployment.changed.v1} consumption end to end against a real Kafka-protocol
 * broker: a valid event produces exactly one deployment record, redelivery of the same event does
 * not produce a second one, and an unprocessable message is routed to the dead-letter topic instead
 * of being retried forever.
 */
class DeploymentEventConsumptionIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ObjectMapper objectMapper;
  @Autowired private DeploymentRepository deploymentRepository;

  private KafkaProducer<String, String> producer() {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new KafkaProducer<>(props);
  }

  private EventEnvelope<DeploymentChangedPayload> envelope(UUID eventId, String correlationId) {
    return new EventEnvelope<>(
        eventId,
        EventTypes.DEPLOYMENT_CHANGED_V1,
        EventTypes.DEPLOYMENT_CHANGED_SCHEMA_VERSION,
        Instant.parse("2026-01-01T00:00:00Z"),
        correlationId,
        "test-ci",
        new DeploymentChangedPayload(
            "deploy-" + eventId,
            "incident-service",
            "1.2.3",
            "local",
            "SUCCEEDED",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:01:00Z"),
            "github-actions",
            null));
  }

  @Test
  void validDeploymentEventProducesExactlyOneDeploymentRecord() throws Exception {
    UUID eventId = UUID.randomUUID();
    String json = objectMapper.writeValueAsString(envelope(eventId, "corr-deploy-1"));

    try (KafkaProducer<String, String> producer = producer()) {
      producer
          .send(new ProducerRecord<>(EventTypes.DEPLOYMENT_CHANGED_V1, eventId.toString(), json))
          .get();
    }

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () ->
                assertThat(deploymentRepository.existsBySourceEventId(eventId.toString()))
                    .isTrue());
  }

  @Test
  void redeliveringTheSameDeploymentEventDoesNotCreateASecondRecord() throws Exception {
    UUID eventId = UUID.randomUUID();
    String json = objectMapper.writeValueAsString(envelope(eventId, "corr-deploy-2"));

    try (KafkaProducer<String, String> producer = producer()) {
      producer
          .send(new ProducerRecord<>(EventTypes.DEPLOYMENT_CHANGED_V1, eventId.toString(), json))
          .get();
      producer
          .send(new ProducerRecord<>(EventTypes.DEPLOYMENT_CHANGED_V1, eventId.toString(), json))
          .get();
    }

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () ->
                assertThat(deploymentRepository.existsBySourceEventId(eventId.toString()))
                    .isTrue());

    Thread.sleep(2000);

    long matching =
        deploymentRepository.findAll().stream()
            .filter(d -> eventId.toString().equals(d.getSourceEventId()))
            .count();
    assertThat(matching).isEqualTo(1);
  }

  @Test
  void unprocessableDeploymentEventIsRoutedToTheDeadLetterTopic() throws Exception {
    String malformedJson = "{ this is not a valid EventEnvelope }";
    String key = "poison-" + UUID.randomUUID();

    try (KafkaProducer<String, String> producer = producer()) {
      producer
          .send(new ProducerRecord<>(EventTypes.DEPLOYMENT_CHANGED_V1, key, malformedJson))
          .get();
    }

    Properties consumerProps = new Properties();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-consumer-" + System.nanoTime());
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
      consumer.subscribe(List.of(EventTypes.DEPLOYMENT_CHANGED_V1_DLQ));
      await()
          .atMost(Duration.ofSeconds(60))
          .untilAsserted(
              () -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> record : records) {
                  if (key.equals(record.key())) {
                    found = true;
                  }
                }
                assertThat(found).isTrue();
              });
    }
  }
}
