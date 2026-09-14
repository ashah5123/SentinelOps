package com.sentinelops.telemetry.infrastructure.kafka;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.OutboxEvent;
import com.sentinelops.telemetry.domain.OutboxStatus;
import com.sentinelops.telemetry.infrastructure.persistence.OutboxEventRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three short, independent database transactions {@link OutboxPublisher} uses around the one
 * network operation (the actual Kafka send) that must never run inside an open transaction — see
 * the identical component and rationale in the incident service.
 */
@Component
class OutboxTransactions {

  private final OutboxEventRepository outboxEventRepository;

  OutboxTransactions(OutboxEventRepository outboxEventRepository) {
    this.outboxEventRepository = outboxEventRepository;
  }

  @Transactional
  List<OutboxEvent> claimBatch(int batchSize, Duration leaseDuration) {
    List<OutboxEvent> claimed = outboxEventRepository.claimBatch(Instant.now(), batchSize);
    Instant leaseUntil = Instant.now().plus(leaseDuration);
    claimed.forEach(event -> event.lease(leaseUntil));
    return claimed;
  }

  @Transactional
  void markPublished(UUID id) {
    outboxEventRepository.findById(id).ifPresent(OutboxEvent::markPublished);
  }

  /** Returns {@code true} if this failure exhausted the retry budget (row is now FAILED). */
  @Transactional
  boolean recordFailure(
      UUID id, String errorMessage, TelemetryCorrelationProperties.Outbox properties) {
    return outboxEventRepository
        .findById(id)
        .map(
            event -> {
              Duration backoff = computeBackoff(event.getAttemptCount(), properties);
              event.recordFailedAttempt(
                  errorMessage, Instant.now().plus(backoff), properties.maxAttempts());
              return event.getStatus() == OutboxStatus.FAILED;
            })
        .orElse(false);
  }

  private Duration computeBackoff(
      int attemptCount, TelemetryCorrelationProperties.Outbox properties) {
    long base =
        Math.min(
            properties.initialBackoff().toMillis() * (1L << Math.min(attemptCount, 10)),
            properties.maxBackoff().toMillis());
    double jitterFraction = properties.backoffJitter();
    if (jitterFraction <= 0) {
      return Duration.ofMillis(base);
    }
    long jitterRange = Math.round(base * jitterFraction);
    long jittered = base + ThreadLocalRandom.current().nextLong(-jitterRange, jitterRange + 1);
    return Duration.ofMillis(Math.max(0, jittered));
  }
}
