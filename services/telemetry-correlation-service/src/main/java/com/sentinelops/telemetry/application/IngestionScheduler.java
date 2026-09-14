package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.observability.Spans;
import com.sentinelops.telemetry.observability.TelemetryMetrics;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs every configured {@link SourceIngestionRunner} for every monitored service on a fixed
 * schedule. Each (source, service) pair is isolated: a failure in one never prevents the others
 * from running in the same cycle (a failed Loki query, for example, must not stop Prometheus
 * evidence from being processed — see the Phase 5 ADR).
 *
 * <p>{@link #guard} provides single-instance backpressure: if a previous cycle is still running
 * when the next scheduled tick fires (e.g. because a backend is unusually slow), the new tick is
 * skipped entirely rather than piling up concurrent cycles. This is a single-JVM guard, sufficient
 * for this service's current single-instance local deployment; a multi-instance deployment would
 * need a distributed lock instead (see the service README's known limitations).
 */
@Component
public class IngestionScheduler {

  private static final Logger log = LoggerFactory.getLogger(IngestionScheduler.class);

  private final List<SourceIngestionRunner> runners;
  private final IngestionCheckpointService checkpointService;
  private final TelemetryCorrelationProperties properties;
  private final TelemetryMetrics metrics;
  private final Spans spans;
  private final AtomicBoolean guard = new AtomicBoolean(false);

  public IngestionScheduler(
      List<SourceIngestionRunner> runners,
      IngestionCheckpointService checkpointService,
      TelemetryCorrelationProperties properties,
      TelemetryMetrics metrics,
      Spans spans) {
    this.runners = runners;
    this.checkpointService = checkpointService;
    this.properties = properties;
    this.metrics = metrics;
    this.spans = spans;
  }

  @Scheduled(fixedDelayString = "${sentinelops.telemetry.ingestion.polling-interval}")
  public void pollAllSources() {
    if (!guard.compareAndSet(false, true)) {
      log.debug("Skipping polling cycle: a previous cycle is still running");
      return;
    }
    try {
      runCycle();
    } finally {
      guard.set(false);
    }
  }

  private void runCycle() {
    for (SourceIngestionRunner runner : runners) {
      for (String monitoredService : properties.monitoredServices()) {
        runOne(runner, monitoredService);
      }
    }
  }

  private void runOne(SourceIngestionRunner runner, String monitoredService) {
    String sourceName = runner.source().name();
    Timer.Sample timer = metrics.startPollingCycleTimer();
    try {
      spans.inSpan(
          "ingestion.poll",
          Map.of("source", sourceName),
          () -> {
            IngestionCheckpointService.IngestionResult result = runner.runFor(monitoredService);
            metrics.recordIngested(sourceName, runner.evidenceType().name(), result.ingested());
            metrics.recordDuplicateIgnored(sourceName, result.duplicates());
            return result;
          });
    } catch (RuntimeException e) {
      log.warn(
          "Ingestion failed for source={} monitoredService={}: {}",
          sourceName,
          monitoredService,
          e.getMessage());
      metrics.pollingFailed(sourceName);
      metrics.backendQueryFailed(sourceName);
      checkpointService.recordFailure(runner.source(), monitoredService);
    } finally {
      metrics.stopPollingCycleTimer(timer, sourceName);
    }
  }
}
