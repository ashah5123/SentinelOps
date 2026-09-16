package com.sentinelops.incident.application;

import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.observability.IncidentMetrics;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Manual dead-letter replay (ADMIN-only, see {@code AdminController}), reusing the same dead-letter
 * topic layout {@code KafkaConfig} already publishes to and the manual inspect-then-replay
 * procedure documented in {@code docs/development/reliability.md}. This class automates exactly
 * that procedure: read up to {@code maxRecords} eligible records from a known {@code .dlq} topic,
 * republish each one unchanged (same key/value/eventId) to its original topic, and only advance the
 * replay consumer group's offset once the republish has been acknowledged.
 *
 * <p>Because a replayed envelope keeps its original {@code eventId}, the target topic's own
 * consumer idempotency check (see {@code AnomalyIncidentProcessor}, {@code
 * EvidenceCorrelatedListener}) still applies — replaying an event that actually did succeed before
 * being (incorrectly) dead-lettered is a safe no-op rather than a duplicate.
 */
@Service
public class DeadLetterReplayService {

  private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayService.class);
  private static final String REPLAY_CONSUMER_GROUP = "incident-service-dlq-replay";
  private static final Duration POLL_TIMEOUT = Duration.ofSeconds(2);
  private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);

  /**
   * The only dead-letter topics this service is able to replay against — both fed by consumers it
   * owns.
   */
  private static final Map<String, String> ELIGIBLE_DLQ_TOPICS =
      Map.of(
          EventTypes.TELEMETRY_ANOMALY_V1_DLQ, EventTypes.TELEMETRY_ANOMALY_V1,
          EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1_DLQ,
              EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1);

  private final KafkaProperties kafkaProperties;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final IncidentMetrics incidentMetrics;

  public DeadLetterReplayService(
      KafkaProperties kafkaProperties,
      KafkaTemplate<String, String> kafkaTemplate,
      IncidentMetrics incidentMetrics) {
    this.kafkaProperties = kafkaProperties;
    this.kafkaTemplate = kafkaTemplate;
    this.incidentMetrics = incidentMetrics;
  }

  public static boolean isEligible(String dlqTopic) {
    return ELIGIBLE_DLQ_TOPICS.containsKey(dlqTopic);
  }

  public static java.util.Set<String> eligibleTopics() {
    return ELIGIBLE_DLQ_TOPICS.keySet();
  }

  public record ReplayResult(String dlqTopic, String targetTopic, int replayed, int failed) {}

  public ReplayResult replay(String dlqTopic, int maxRecords) {
    String targetTopic = ELIGIBLE_DLQ_TOPICS.get(dlqTopic);
    if (targetTopic == null) {
      throw new IneligibleDeadLetterTopicException(dlqTopic);
    }

    int replayed = 0;
    int failed = 0;
    try (KafkaConsumer<String, String> consumer = replayConsumer()) {
      consumer.subscribe(List.of(dlqTopic));
      // One bounded poll cycle is enough to pick up whatever partitions/records are currently
      // assigned; a follow-up admin call replays the next batch rather than looping unbounded
      // inside a single HTTP request.
      ConsumerRecords<String, String> records = consumer.poll(POLL_TIMEOUT);
      for (ConsumerRecord<String, String> record : records) {
        if (replayed + failed >= maxRecords) {
          break;
        }
        try {
          kafkaTemplate
              .send(targetTopic, record.key(), record.value())
              .get(SEND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
          consumer.commitSync(Duration.ofSeconds(5));
          replayed++;
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
          if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
          }
          log.warn("Failed to replay dead-lettered record from {}", dlqTopic, e);
          failed++;
          break; // stop at the first failure so offsets stay consistent with what was replayed
        }
      }
    }
    incidentMetrics.outboxPublished(targetTopic, failed == 0 ? "success" : "failure");
    return new ReplayResult(dlqTopic, targetTopic, replayed, failed);
  }

  private KafkaConsumer<String, String> replayConsumer() {
    Map<String, Object> props = new HashMap<>(kafkaProperties.buildConsumerProperties(null));
    props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    props.put(ConsumerConfig.GROUP_ID_CONFIG, REPLAY_CONSUMER_GROUP);
    props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    return new KafkaConsumer<>(props);
  }

  /** Thrown when an admin requests replay of a topic this service has no consumer for. */
  public static class IneligibleDeadLetterTopicException extends RuntimeException {
    private final String topic;

    public IneligibleDeadLetterTopicException(String topic) {
      super("Not an eligible dead-letter topic for replay: " + topic);
      this.topic = topic;
    }

    public String topic() {
      return topic;
    }
  }
}
