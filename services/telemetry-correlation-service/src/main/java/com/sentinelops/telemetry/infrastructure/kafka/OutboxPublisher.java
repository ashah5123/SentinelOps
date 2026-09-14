package com.sentinelops.telemetry.infrastructure.kafka;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.OutboxEvent;
import com.sentinelops.telemetry.domain.OutboxStatus;
import com.sentinelops.telemetry.infrastructure.persistence.OutboxEventRepository;
import com.sentinelops.telemetry.observability.Spans;
import com.sentinelops.telemetry.observability.TelemetryMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Publishes transactional outbox rows to Kafka. Mirrors the incident service's own {@code
 * OutboxPublisher}: claiming, the actual network send, and finalizing the row's status are three
 * separate, short-lived operations (see {@link OutboxTransactions}) rather than one long-running
 * transaction, so the database transaction and the {@code FOR UPDATE} row lock are never held open
 * for the duration of a Kafka network call. A claimed row is instead protected from re-claiming by
 * a short lease (see {@link OutboxEvent#lease(Instant)}).
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxEventRepository outboxEventRepository;
  private final OutboxTransactions transactions;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final TelemetryCorrelationProperties.Outbox properties;
  private final TelemetryMetrics metrics;
  private final Spans spans;

  public OutboxPublisher(
      OutboxEventRepository outboxEventRepository,
      OutboxTransactions transactions,
      KafkaTemplate<String, String> kafkaTemplate,
      TelemetryCorrelationProperties properties,
      TelemetryMetrics metrics,
      Spans spans) {
    this.outboxEventRepository = outboxEventRepository;
    this.transactions = transactions;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties.outbox();
    this.metrics = metrics;
    this.spans = spans;
  }

  @Scheduled(fixedDelayString = "${sentinelops.telemetry.outbox.polling-interval}")
  public void publishDueEvents() {
    List<OutboxEvent> batch =
        transactions.claimBatch(properties.batchSize(), properties.leaseDuration());
    for (OutboxEvent event : batch) {
      publishOne(event);
    }
  }

  private void publishOne(OutboxEvent event) {
    String topic = event.getTopic();
    spans.inSpan(
        "outbox.publish", Map.of("topic", topic), () -> publishOutsideTransaction(event, topic));
  }

  private void publishOutsideTransaction(OutboxEvent event, String topic) {
    UUID id = event.getId();
    var timerSample = metrics.startOutboxPublishTimer();
    try {
      kafkaTemplate
          .send(topic, event.getAggregateId().toString(), event.getPayload())
          .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
      transactions.markPublished(id);
      metrics.outboxPublished(topic, "success");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handleFailure(id, topic, e);
    } catch (ExecutionException | TimeoutException e) {
      handleFailure(id, topic, e);
    } finally {
      metrics.stopOutboxPublishTimer(timerSample, topic);
    }
  }

  private void handleFailure(UUID id, String topic, Exception e) {
    metrics.outboxPublished(topic, "failure");
    log.warn("Failed to publish outbox event {} (topic={}): {}", id, topic, e.getMessage());
    boolean deadLettered = transactions.recordFailure(id, e.getMessage(), properties);
    if (deadLettered) {
      metrics.outboxDeadLettered(topic);
      log.error(
          "Outbox event {} (topic={}) exhausted its retry budget and moved to FAILED", id, topic);
    }
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
