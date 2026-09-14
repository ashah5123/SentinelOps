package com.sentinelops.telemetry.infrastructure.kafka;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.OutboxEvent;
import com.sentinelops.telemetry.domain.OutboxStatus;
import com.sentinelops.telemetry.infrastructure.persistence.OutboxEventRepository;
import com.sentinelops.telemetry.observability.Spans;
import com.sentinelops.telemetry.observability.TelemetryMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes transactional outbox rows to Kafka. Mirrors the incident service's own {@code
 * OutboxPublisher}, including its concurrency-safe {@code SELECT ... FOR UPDATE SKIP LOCKED}
 * claiming strategy and at-least-once duplicate-delivery assumption (see ADR 0007).
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private static final int KAFKA_SEND_TIMEOUT_SECONDS = 10;

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final TelemetryCorrelationProperties.Outbox properties;
  private final TelemetryMetrics metrics;
  private final Spans spans;

  public OutboxPublisher(
      OutboxEventRepository outboxEventRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      TelemetryCorrelationProperties properties,
      TelemetryMetrics metrics,
      Spans spans) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties.outbox();
    this.metrics = metrics;
    this.spans = spans;
  }

  @Scheduled(fixedDelayString = "${sentinelops.telemetry.outbox.polling-interval}")
  @Transactional
  public void publishDueEvents() {
    List<OutboxEvent> batch =
        outboxEventRepository.claimBatch(Instant.now(), properties.batchSize());
    for (OutboxEvent event : batch) {
      publishOne(event);
    }
  }

  private void publishOne(OutboxEvent event) {
    String topic = event.getTopic();
    spans.inSpan("outbox.publish", Map.of("topic", topic), () -> publishOneInSpan(event, topic));
  }

  private void publishOneInSpan(OutboxEvent event, String topic) {
    try {
      kafkaTemplate
          .send(topic, event.getAggregateId().toString(), event.getPayload())
          .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      event.markPublished();
      metrics.outboxPublished(topic, "success");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      metrics.outboxPublished(topic, "failure");
      recordFailure(event, e);
    } catch (ExecutionException | TimeoutException e) {
      metrics.outboxPublished(topic, "failure");
      recordFailure(event, e);
    }
  }

  private void recordFailure(OutboxEvent event, Exception e) {
    Duration backoff =
        Duration.ofMillis(
            Math.min(
                properties.initialBackoff().toMillis()
                    * (1L << Math.min(event.getAttemptCount(), 10)),
                properties.maxBackoff().toMillis()));
    log.warn(
        "Failed to publish outbox event {} (topic={}, attempt={}): {}",
        event.getId(),
        event.getTopic(),
        event.getAttemptCount() + 1,
        e.getMessage());
    event.recordFailedAttempt(
        e.getMessage(), Instant.now().plus(backoff), properties.maxAttempts());
  }

  /** Deletes PUBLISHED outbox rows older than the configured retention window. */
  @Scheduled(fixedDelayString = "${sentinelops.telemetry.outbox.retention-after-publish}")
  public void purgePublished() {
    Instant cutoff = Instant.now().minus(properties.retentionAfterPublish());
    long purged =
        outboxEventRepository.deleteByStatusAndPublishedAtBefore(OutboxStatus.PUBLISHED, cutoff);
    if (purged > 0) {
      log.info("Purged {} published outbox events older than retention window", purged);
    }
  }
}
