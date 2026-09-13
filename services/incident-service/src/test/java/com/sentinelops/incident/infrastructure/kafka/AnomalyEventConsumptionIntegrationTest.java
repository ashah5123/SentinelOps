package com.sentinelops.incident.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.events.EventEnvelope;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.events.TelemetryAnomalyPayload;
import com.sentinelops.incident.infrastructure.persistence.IncidentRepository;
import com.sentinelops.incident.infrastructure.persistence.ProcessedEventRepository;
import com.sentinelops.incident.support.AbstractIntegrationTest;
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
 * Verifies anomaly consumption end to end against a real Kafka-protocol broker: a valid anomaly
 * produces exactly one incident, redelivery of the same event does not produce a second one, and a
 * message that can never be processed successfully ends up on the dead-letter topic instead of
 * being retried forever.
 */
class AnomalyEventConsumptionIntegrationTest extends AbstractIntegrationTest {

  @Autowired private ObjectMapper objectMapper;
  @Autowired private IncidentRepository incidentRepository;
  @Autowired private ProcessedEventRepository processedEventRepository;

  private KafkaProducer<String, String> producer() {
    Properties props = new Properties();
    props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    return new KafkaProducer<>(props);
  }

  @Test
  void validAnomalyEventProducesExactlyOneIncident() throws Exception {
    UUID eventId = UUID.randomUUID();
    EventEnvelope<TelemetryAnomalyPayload> envelope =
        new EventEnvelope<>(
            eventId,
            EventTypes.TELEMETRY_ANOMALY_V1,
            1,
            Instant.parse("2026-09-12T18:00:00Z"),
            "corr-anomaly-1",
            "test-detector",
            new TelemetryAnomalyPayload(
                "High latency",
                "desc",
                "SEV2",
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

    assertThat(processedEventRepository.existsBySourceEventId(eventId.toString())).isTrue();
  }

  @Test
  void redeliveringTheSameAnomalyEventDoesNotCreateASecondIncident() throws Exception {
    UUID eventId = UUID.randomUUID();
    EventEnvelope<TelemetryAnomalyPayload> envelope =
        new EventEnvelope<>(
            eventId,
            EventTypes.TELEMETRY_ANOMALY_V1,
            1,
            Instant.parse("2026-09-12T18:00:00Z"),
            "corr-anomaly-2",
            "test-detector",
            new TelemetryAnomalyPayload(
                "Duplicate-prone anomaly",
                "desc",
                "SEV3",
                "checkout-api",
                Instant.parse("2026-09-12T18:00:00Z")));
    String json = objectMapper.writeValueAsString(envelope);

    try (KafkaProducer<String, String> producer = producer()) {
      // Simulate at-least-once redelivery: the same event ID is published twice.
      producer
          .send(new ProducerRecord<>(EventTypes.TELEMETRY_ANOMALY_V1, eventId.toString(), json))
          .get();
      producer
          .send(new ProducerRecord<>(EventTypes.TELEMETRY_ANOMALY_V1, eventId.toString(), json))
          .get();
    }

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () ->
                assertThat(incidentRepository.findBySourceEventId(eventId.toString())).isPresent());

    // Give the second delivery time to be processed (or ignored) before asserting the count.
    Thread.sleep(2000);

    long matchingIncidents =
        incidentRepository.findAll().stream()
            .filter(i -> eventId.toString().equals(i.getSourceEventId()))
            .count();
    assertThat(matchingIncidents).isEqualTo(1);
  }

  @Test
  void unprocessableAnomalyEventIsRoutedToTheDeadLetterTopicAfterRetriesAreExhausted() {
    String malformedJson = "{ this is not a valid EventEnvelope }";
    String key = "poison-" + UUID.randomUUID();

    try (KafkaProducer<String, String> producer = producer()) {
      producer
          .send(new ProducerRecord<>(EventTypes.TELEMETRY_ANOMALY_V1, key, malformedJson))
          .get();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    Properties consumerProps = new Properties();
    consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-consumer-" + System.nanoTime());
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
      consumer.subscribe(List.of(EventTypes.TELEMETRY_ANOMALY_V1_DLQ));
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
