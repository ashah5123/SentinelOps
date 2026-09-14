package com.sentinelops.telemetry.observability;

import com.sentinelops.telemetry.domain.OutboxStatus;
import com.sentinelops.telemetry.infrastructure.persistence.OutboxEventRepository;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Registers live gauges for the outbox backlog — see the identical component in the incident
 * service. Each gauge value is computed lazily on every Prometheus scrape, not cached.
 */
@Component
public class OutboxBacklogMetrics {

  private final OutboxEventRepository outboxEventRepository;
  private final TelemetryMetrics telemetryMetrics;

  public OutboxBacklogMetrics(
      OutboxEventRepository outboxEventRepository, TelemetryMetrics telemetryMetrics) {
    this.outboxEventRepository = outboxEventRepository;
    this.telemetryMetrics = telemetryMetrics;
  }

  @PostConstruct
  void registerGauges() {
    telemetryMetrics.bindOutboxBacklogGauges(
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
