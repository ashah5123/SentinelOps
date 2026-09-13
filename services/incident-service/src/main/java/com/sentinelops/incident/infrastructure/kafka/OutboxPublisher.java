package com.sentinelops.incident.infrastructure.kafka;

import com.sentinelops.incident.config.IncidentServiceProperties;
import com.sentinelops.incident.domain.OutboxEvent;
import com.sentinelops.incident.domain.OutboxStatus;
import com.sentinelops.incident.infrastructure.persistence.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
 * Publishes transactional outbox rows to Kafka.
 *
 * <p>Claiming uses PostgreSQL's {@code SELECT ... FOR UPDATE SKIP LOCKED} (see {@link
 * OutboxEventRepository#claimBatch}), so concurrent instances of this service never publish the
 * same row simultaneously under normal operation — each instance only ever sees rows the others
 * have not locked.
 *
 * <p><b>Duplicate-delivery assumption:</b> if this process crashes after a Kafka send succeeds but
 * before the database transaction that marks the row {@code PUBLISHED} commits, the row remains
 * {@code PENDING} and will be republished on the next poll. Consumers of {@code
 * incident.detected.v1} and {@code audit.event.v1} must therefore treat delivery as at-least-once
 * and be safe to process the same event more than once (see ADR 0008).
 *
 * <p>A row that exhausts {@code maxAttempts} is marked {@code FAILED} and left in the table for
 * manual inspection — see {@code docs/development/local-platform.md} and the incident-service
 * README for the inspection query. Retention/cleanup of already-published rows is handled by {@link
 * #purgePublished()}, which never removes anything published within the configured retention
 * window.
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
  private static final int KAFKA_SEND_TIMEOUT_SECONDS = 10;

  private final OutboxEventRepository outboxEventRepository;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final IncidentServiceProperties.Outbox properties;

  public OutboxPublisher(
      OutboxEventRepository outboxEventRepository,
      KafkaTemplate<String, String> kafkaTemplate,
      IncidentServiceProperties properties) {
    this.outboxEventRepository = outboxEventRepository;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties.outbox();
  }

  @Scheduled(fixedDelayString = "${sentinelops.incident-service.outbox.polling-interval}")
  @Transactional
  public void publishDueEvents() {
    List<OutboxEvent> batch =
        outboxEventRepository.claimBatch(Instant.now(), properties.batchSize());
    for (OutboxEvent event : batch) {
      publishOne(event);
    }
  }

  private void publishOne(OutboxEvent event) {
    try {
      kafkaTemplate
          .send(event.getTopic(), event.getAggregateId().toString(), event.getPayload())
          .get(KAFKA_SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      event.markPublished();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      recordFailure(event, e);
    } catch (ExecutionException | TimeoutException e) {
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

  /**
   * Deletes PUBLISHED outbox rows older than the configured retention window. Never touches PENDING
   * or FAILED rows, and never deletes anything published within the retention window, so recent
   * evidence of what was published remains inspectable.
   */
  @Scheduled(fixedDelayString = "${sentinelops.incident-service.outbox.retention-after-publish}")
  public void purgePublished() {
    Instant cutoff = Instant.now().minus(properties.retentionAfterPublish());
    long purged =
        outboxEventRepository.deleteByStatusAndPublishedAtBefore(OutboxStatus.PUBLISHED, cutoff);
    if (purged > 0) {
      log.info("Purged {} published outbox events older than retention window", purged);
    }
  }
}
