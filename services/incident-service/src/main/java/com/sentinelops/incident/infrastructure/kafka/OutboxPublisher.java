package com.sentinelops.incident.infrastructure.kafka;

import com.sentinelops.incident.config.IncidentServiceProperties;
import com.sentinelops.incident.domain.OutboxEvent;
import com.sentinelops.incident.domain.OutboxStatus;
import com.sentinelops.incident.infrastructure.persistence.OutboxEventRepository;
import com.sentinelops.incident.observability.IncidentMetrics;
import com.sentinelops.incident.observability.Spans;
import io.micrometer.core.instrument.Timer;
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
 * Publishes transactional outbox rows to Kafka.
 *
 * <p>Claiming uses PostgreSQL's {@code SELECT ... FOR UPDATE SKIP LOCKED} (see {@link
 * OutboxEventRepository#claimBatch}), so concurrent instances of this service never publish the
 * same row simultaneously under normal operation. The claim, the actual network send, and the final
 * status update are three separate, short-lived operations — see {@link OutboxTransactions} —
 * rather than one long-running transaction, so the database transaction and the {@code FOR UPDATE}
 * row lock are never held open for the duration of a Kafka network call. Instead, a claimed row is
 * protected from re-claiming by a short lease (see {@link OutboxEvent#lease(Instant)}); if this
 * process crashes between claiming and finalizing, the row simply becomes claimable again once the
 * lease expires.
 *
 * <p><b>Duplicate-delivery assumption:</b> if this process crashes after a Kafka send succeeds but
 * before the row is marked {@code PUBLISHED}, the row remains claimable and will be republished on
 * a later poll. Consumers of {@code incident.detected.v1}, {@code audit.event.v1}, and {@code
 * incident.evidence.correlated.v1} must therefore treat delivery as at-least-once and be safe to
 * process the same event more than once (see ADR 0008).
 *
 * <p>A row that exhausts {@code maxAttempts} is marked {@code FAILED} and left in the table for
 * manual inspection — see {@code docs/development/reliability.md} for the inspection and replay
 * procedure. Retention/cleanup of already-published rows is handled by {@link #purgePublished()},
 * which never removes anything published within the configured retention window.
 */
@Component
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxEventRepository outboxEventRepository;
  private final OutboxTransactions transactions;
  private final KafkaTemplate<String, String> kafkaTemplate;
  private final IncidentServiceProperties.Outbox properties;
  private final IncidentMetrics incidentMetrics;
  private final Spans spans;

  public OutboxPublisher(
      OutboxEventRepository outboxEventRepository,
      OutboxTransactions transactions,
      KafkaTemplate<String, String> kafkaTemplate,
      IncidentServiceProperties properties,
      IncidentMetrics incidentMetrics,
      Spans spans) {
    this.outboxEventRepository = outboxEventRepository;
    this.transactions = transactions;
    this.kafkaTemplate = kafkaTemplate;
    this.properties = properties.outbox();
    this.incidentMetrics = incidentMetrics;
    this.spans = spans;
  }

  @Scheduled(fixedDelayString = "${sentinelops.incident-service.outbox.polling-interval}")
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

  /**
   * Performs the Kafka send with no database transaction open, then finalizes the row's status in
   * its own short transaction — see the class-level note on why this is split this way.
   */
  private void publishOutsideTransaction(OutboxEvent event, String topic) {
    UUID id = event.getId();
    Timer.Sample timerSample = incidentMetrics.startOutboxPublishTimer();
    try {
      kafkaTemplate
          .send(topic, event.getAggregateId().toString(), event.getPayload())
          .get(properties.publishTimeout().toMillis(), TimeUnit.MILLISECONDS);
      transactions.markPublished(id);
      incidentMetrics.outboxPublished(topic, "success");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      handleFailure(id, topic, e);
    } catch (ExecutionException | TimeoutException e) {
      handleFailure(id, topic, e);
    } finally {
      incidentMetrics.stopOutboxPublishTimer(timerSample, topic);
    }
  }

  private void handleFailure(UUID id, String topic, Exception e) {
    incidentMetrics.outboxPublished(topic, "failure");
    log.warn("Failed to publish outbox event {} (topic={}): {}", id, topic, e.getMessage());
    boolean deadLettered = transactions.recordFailure(id, e.getMessage(), properties);
    if (deadLettered) {
      incidentMetrics.outboxDeadLettered(topic);
      log.error(
          "Outbox event {} (topic={}) exhausted its retry budget and moved to FAILED", id, topic);
    }
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
