package com.sentinelops.incident.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.sentinelops.incident.application.DeadLetterReplayService.IneligibleDeadLetterTopicException;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.observability.IncidentMetrics;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * Uses an in-process embedded broker (no Docker required) rather than Testcontainers, since what is
 * under test is the replay logic itself, not broker fidelity to the real Redpanda/Kafka protocol
 * (already covered elsewhere by the Testcontainers-based Kafka integration tests).
 */
@SpringJUnitConfig(classes = DeadLetterReplayServiceTest.EmptyTestConfig.class)
@EmbeddedKafka(
    partitions = 1,
    topics = {EventTypes.TELEMETRY_ANOMALY_V1_DLQ, EventTypes.TELEMETRY_ANOMALY_V1})
class DeadLetterReplayServiceTest {

  @org.springframework.context.annotation.Configuration
  static class EmptyTestConfig {}

  @Autowired private EmbeddedKafkaBroker broker;

  private DeadLetterReplayService service() {
    KafkaProperties kafkaProperties = new KafkaProperties();
    kafkaProperties.setBootstrapServers(List.of(broker.getBrokersAsString()));

    Map<String, Object> producerProps = new HashMap<>(KafkaTestUtils.producerProps(broker));
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    KafkaTemplate<String, String> kafkaTemplate =
        new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProps));

    return new DeadLetterReplayService(kafkaProperties, kafkaTemplate, mock(IncidentMetrics.class));
  }

  private void publishToDlq(String key, String value) throws Exception {
    Map<String, Object> producerProps = new HashMap<>(KafkaTestUtils.producerProps(broker));
    producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    try (KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps)) {
      producer
          .send(new ProducerRecord<>(EventTypes.TELEMETRY_ANOMALY_V1_DLQ, key, value))
          .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  private ConsumerRecords<String, String> pollTargetTopic() {
    Map<String, Object> consumerProps =
        new HashMap<>(KafkaTestUtils.consumerProps("dlq-test-verifier", "true", broker));
    consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps)) {
      consumer.subscribe(List.of(EventTypes.TELEMETRY_ANOMALY_V1));
      return consumer.poll(Duration.ofSeconds(10));
    }
  }

  @Test
  void isEligibleOnlyForTopicsThisServiceConsumes() {
    assertThat(DeadLetterReplayService.isEligible(EventTypes.TELEMETRY_ANOMALY_V1_DLQ)).isTrue();
    assertThat(DeadLetterReplayService.isEligible(EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1_DLQ))
        .isTrue();
    assertThat(DeadLetterReplayService.isEligible("unrelated.topic.v1.dlq")).isFalse();
  }

  @Test
  void replayingAnIneligibleTopicIsRejectedWithoutTouchingKafka() {
    assertThatThrownBy(() -> service().replay("unrelated.topic.v1.dlq", 10))
        .isInstanceOf(IneligibleDeadLetterTopicException.class);
  }

  @Test
  void replayRepublishesADeadLetteredRecordToItsOriginalTopicUnchanged() throws Exception {
    publishToDlq("incident-42", "{\"eventId\":\"abc-123\"}");

    DeadLetterReplayService.ReplayResult result =
        service().replay(EventTypes.TELEMETRY_ANOMALY_V1_DLQ, 10);

    assertThat(result.replayed()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(0);
    assertThat(result.targetTopic()).isEqualTo(EventTypes.TELEMETRY_ANOMALY_V1);

    ConsumerRecords<String, String> records = pollTargetTopic();
    assertThat(records.count()).isEqualTo(1);
    ConsumerRecord<String, String> replayed = records.iterator().next();
    assertThat(replayed.key()).isEqualTo("incident-42");
    assertThat(replayed.value()).isEqualTo("{\"eventId\":\"abc-123\"}");
  }
}
