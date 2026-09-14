package com.sentinelops.telemetry.infrastructure.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.sentinelops.telemetry.domain.OutboxEvent;
import com.sentinelops.telemetry.domain.OutboxStatus;
import com.sentinelops.telemetry.events.EventTypes;
import com.sentinelops.telemetry.infrastructure.persistence.OutboxEventRepository;
import com.sentinelops.telemetry.support.AbstractIntegrationTest;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the transactional outbox publishes a PENDING row to the real broker and marks it
 * PUBLISHED — see the identical test and rationale in the incident service. Also verifies a broker
 * outage leaves the row recoverable and publishing resumes once the broker is reachable again.
 */
class OutboxPublisherIntegrationTest extends AbstractIntegrationTest {

  @Autowired private OutboxEventRepository outboxEventRepository;

  private OutboxEvent pendingRow(String correlationId) {
    return OutboxEvent.pending(
        "CorrelationResult",
        UUID.randomUUID(),
        EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1,
        EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1,
        EventTypes.INCIDENT_EVIDENCE_CORRELATED_SCHEMA_VERSION,
        "{\"outboxIntegrationTest\":true}",
        correlationId);
  }

  @Test
  void aPendingRowIsPublishedAndReceivableFromTheBroker() {
    OutboxEvent row = pendingRow("corr-telemetry-outbox-1");
    outboxEventRepository.saveAndFlush(row);

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () ->
                assertThat(outboxEventRepository.findById(row.getId()).orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PUBLISHED));

    Properties props = new Properties();
    props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-consumer-" + System.nanoTime());
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
      consumer.subscribe(List.of(EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1));
      await()
          .atMost(awaitTimeout())
          .untilAsserted(
              () -> {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                boolean found = false;
                for (ConsumerRecord<String, String> record : records) {
                  if (record.value().contains("outboxIntegrationTest")) {
                    found = true;
                  }
                }
                assertThat(found).isTrue();
              });
    }
  }

  @Test
  void aPendingRowSurvivesABrokerOutageAndPublishesOnceItRecovers() {
    OutboxEvent row = pendingRow("corr-telemetry-outbox-outage-1");

    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      outboxEventRepository.saveAndFlush(row);

      await()
          .atMost(Duration.ofSeconds(5))
          .untilAsserted(() -> assertThat(outboxEventRepository.findById(row.getId())).isPresent());
      assertThat(outboxEventRepository.findById(row.getId()).orElseThrow().getStatus())
          .isNotEqualTo(OutboxStatus.PUBLISHED);
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    await()
        .atMost(awaitTimeout())
        .untilAsserted(
            () ->
                assertThat(outboxEventRepository.findById(row.getId()).orElseThrow().getStatus())
                    .isEqualTo(OutboxStatus.PUBLISHED));
  }
}
