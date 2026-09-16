package com.sentinelops.incident.alerts.notification;

import com.sentinelops.incident.alerts.AlertsProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short, independent transactions around the one operation (the actual channel send) that must
 * never run inside an open database transaction — see {@code OutboxTransactions} for the same split
 * applied to Kafka publishing.
 */
@Component
class NotificationTransactions {

  private final NotificationRepository repository;
  private final AlertsProperties.Notification properties;

  NotificationTransactions(NotificationRepository repository, AlertsProperties properties) {
    this.repository = repository;
    this.properties = properties.notification();
  }

  @Transactional
  List<NotificationRow> claimBatch(int batchSize, Duration leaseDuration) {
    List<NotificationRow> claimed = repository.claimDueBatch(batchSize, Instant.now());
    if (!claimed.isEmpty()) {
      repository.lease(
          claimed.stream().map(NotificationRow::id).toList(), Instant.now().plus(leaseDuration));
    }
    return claimed;
  }

  @Transactional
  void markSent(UUID id) {
    repository.markSent(id, Instant.now());
  }

  /** Returns {@code true} if this failure exhausted the retry budget (row is now DEAD_LETTERED). */
  @Transactional
  boolean recordFailure(UUID id, int attemptCountBeforeThisFailure, String sanitizedError) {
    Duration backoff = computeBackoff(attemptCountBeforeThisFailure);
    return repository.recordFailure(
        id, sanitizedError, Instant.now().plus(backoff), properties.maxAttempts());
  }

  @Transactional
  void deadLetter(UUID id, String sanitizedError) {
    repository.deadLetter(id, sanitizedError);
  }

  private Duration computeBackoff(int attemptCount) {
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
