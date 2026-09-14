package com.sentinelops.telemetry.infrastructure.kafka;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.infrastructure.persistence.ProcessedEventRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes {@code processed_events} idempotency records older than the configured retention window —
 * mirrors the incident service's own {@code ProcessedEventRetention}. This service's current Kafka
 * consumers track idempotency via their own domain tables' unique constraints (deployments,
 * dependency history, correlation results) rather than this generic table, so this job is a no-op
 * today; it exists so a future consumer using {@code ProcessedEventRepository} directly does not
 * need its own retention story.
 */
@Component
public class ProcessedEventRetention {

  private static final Logger log = LoggerFactory.getLogger(ProcessedEventRetention.class);

  private final ProcessedEventRepository processedEventRepository;
  private final TelemetryCorrelationProperties.Consumer properties;

  public ProcessedEventRetention(
      ProcessedEventRepository processedEventRepository,
      TelemetryCorrelationProperties properties) {
    this.processedEventRepository = processedEventRepository;
    this.properties = properties.consumer();
  }

  @Scheduled(
      fixedDelayString = "${sentinelops.telemetry.consumer.processed-event-cleanup-interval}")
  public void purgeExpired() {
    Instant cutoff = Instant.now().minus(properties.processedEventRetention());
    long purged = processedEventRepository.deleteByProcessedAtBefore(cutoff);
    if (purged > 0) {
      log.info("Purged {} processed-event idempotency records older than retention window", purged);
    }
  }
}
