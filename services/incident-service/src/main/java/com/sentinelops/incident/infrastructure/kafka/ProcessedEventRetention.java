package com.sentinelops.incident.infrastructure.kafka;

import com.sentinelops.incident.config.IncidentServiceProperties;
import com.sentinelops.incident.infrastructure.persistence.ProcessedEventRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes {@code processed_events} idempotency records older than the configured retention window
 * (default 8 days — comfortably longer than the broker's own topic retention, so a message somehow
 * redelivered after a long delay is still recognized and safely ignored as a duplicate rather than
 * reprocessed). Without this, the idempotency table would grow without bound for the lifetime of
 * the service.
 */
@Component
public class ProcessedEventRetention {

  private static final Logger log = LoggerFactory.getLogger(ProcessedEventRetention.class);

  private final ProcessedEventRepository processedEventRepository;
  private final IncidentServiceProperties.Consumer properties;

  public ProcessedEventRetention(
      ProcessedEventRepository processedEventRepository, IncidentServiceProperties properties) {
    this.processedEventRepository = processedEventRepository;
    this.properties = properties.consumer();
  }

  @Scheduled(
      fixedDelayString =
          "${sentinelops.incident-service.consumer.processed-event-cleanup-interval}")
  public void purgeExpired() {
    Instant cutoff = Instant.now().minus(properties.processedEventRetention());
    long purged = processedEventRepository.deleteByProcessedAtBefore(cutoff);
    if (purged > 0) {
      log.info("Purged {} processed-event idempotency records older than retention window", purged);
    }
  }
}
