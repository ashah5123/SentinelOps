package com.sentinelops.incident.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinelops.incident.application.CreateIncidentCommand;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.OutboxStatus;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.infrastructure.persistence.OutboxEventRepository;
import com.sentinelops.incident.support.AbstractIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the transactional outbox: a domain write produces a PENDING row, and the background
 * publisher publishes it to the real (Kafka-protocol) broker and marks it PUBLISHED.
 */
class OutboxPublisherIntegrationTest extends AbstractIntegrationTest {

  @Autowired private IncidentCommandService incidentCommandService;
  @Autowired private OutboxEventRepository outboxEventRepository;

  @Test
  void incidentCreationProducesAnOutboxRowThatGetsPublished() {
    Incident incident =
        incidentCommandService.createIncident(
            new CreateIncidentCommand(
                "Outbox test incident",
                "desc",
                IncidentSeverity.SEV2,
                "manual-report",
                "checkout-api",
                Instant.parse("2026-09-12T18:00:00Z"),
                "corr-outbox-1",
                null,
                ActorType.LOCAL_USER,
                "local-operator"));

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () -> {
              var rows = outboxEventRepository.findAll();
              boolean publishedIncidentDetected =
                  rows.stream()
                      .anyMatch(
                          row ->
                              row.getAggregateId().equals(incident.getId())
                                  && row.getTopic().equals(EventTypes.INCIDENT_DETECTED_V1)
                                  && row.getStatus() == OutboxStatus.PUBLISHED);
              assertThat(publishedIncidentDetected).isTrue();
            });
  }

  @Test
  void publishedIncidentDetectedEventIsActuallyReceivableFromTheBroker() {
    Incident incident =
        incidentCommandService.createIncident(
            new CreateIncidentCommand(
                "Broker delivery test",
                "desc",
                IncidentSeverity.SEV1,
                "manual-report",
                "payments-api",
                Instant.parse("2026-09-12T18:00:00Z"),
                "corr-outbox-2",
                null,
                ActorType.LOCAL_USER,
                "local-operator"));

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(EventTypes.INCIDENT_DETECTED_V1));
      await()
          .atMost(awaitTimeout())
          .untilAsserted(
              () -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> record : records) {
                  if (record.value().contains(incident.getId().toString())) {
                    found = true;
                  }
                }
                assertThat(found).isTrue();
              });
    }
  }
}
