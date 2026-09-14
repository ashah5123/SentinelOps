package com.sentinelops.incident.observability;

import com.sentinelops.incident.domain.OutboxStatus;
import com.sentinelops.incident.infrastructure.persistence.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Registers live gauges for the outbox backlog (see {@code docs/development/observability.md}): how
 * many rows are waiting to be published, and how old the oldest of them is. Each gauge value is
 * computed lazily on every Prometheus scrape, not cached, so it always reflects the current
 * database state.
 */
@Component
public class OutboxBacklogMetrics {

  private final OutboxEventRepository outboxEventRepository;
  private final IncidentMetrics incidentMetrics;

  public OutboxBacklogMetrics(
      OutboxEventRepository outboxEventRepository, IncidentMetrics incidentMetrics) {
    this.outboxEventRepository = outboxEventRepository;
    this.incidentMetrics = incidentMetrics;
  }

  @PostConstruct
  void registerGauges() {
    incidentMetrics.bindOutboxBacklogGauges(
        () -> outboxEventRepository.countByStatus(OutboxStatus.PENDING),
        this::oldestPendingAgeSeconds);
  }

  private double oldestPendingAgeSeconds() {
    return outboxEventRepository
        .findOldestCreatedAtByStatus(OutboxStatus.PENDING)
        .map(oldest -> Duration.between(oldest, Instant.now()).toSeconds())
        .map(Long::doubleValue)
        .orElse(0.0);
  }
}
