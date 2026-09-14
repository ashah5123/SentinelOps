package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.adapters.loki.LokiClient;
import com.sentinelops.telemetry.adapters.loki.LokiNormalizer;
import com.sentinelops.telemetry.adapters.loki.LokiResponse;
import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Incremental Loki ingestion: runs the configured LogQL query for one monitored service over the
 * window since its last checkpoint, normalizes and deduplicates the results, and advances the
 * checkpoint. A failure here never prevents Prometheus or Tempo ingestion for the same cycle — see
 * {@code IngestionScheduler}.
 */
@Component
public class LokiIngestionRunner implements SourceIngestionRunner {

  private final LokiClient client;
  private final LokiNormalizer normalizer;
  private final IngestionCheckpointService checkpointService;
  private final FingerprintService fingerprintService;
  private final TelemetryCorrelationProperties properties;

  public LokiIngestionRunner(
      LokiClient client,
      LokiNormalizer normalizer,
      IngestionCheckpointService checkpointService,
      FingerprintService fingerprintService,
      TelemetryCorrelationProperties properties) {
    this.client = client;
    this.normalizer = normalizer;
    this.checkpointService = checkpointService;
    this.fingerprintService = fingerprintService;
    this.properties = properties;
  }

  @Override
  public SourceSystem source() {
    return SourceSystem.LOKI;
  }

  @Override
  public EvidenceType evidenceType() {
    return EvidenceType.LOG;
  }

  @Override
  public IngestionCheckpointService.IngestionResult runFor(String monitoredService) {
    TelemetryCorrelationProperties.Ingestion ingestion = properties.ingestion();
    Instant now = Instant.now();
    Instant watermark =
        checkpointService.currentWatermark(
            source(), monitoredService, now.minus(ingestion.queryWindow()));
    Instant from = watermark.minus(ingestion.overlapWindow());

    String logQlQuery = properties.lokiQuery().formatted(monitoredService);
    LokiResponse response = client.queryRange(logQlQuery, from, now);

    List<Evidence> candidates =
        normalizer.normalize(logQlQuery, monitoredService, response).stream()
            .map(builder -> finish(builder, monitoredService))
            .toList();

    return checkpointService.persistAndAdvance(
        source(), monitoredService, now, candidates, ingestion.maxRecordsPerCycle());
  }

  private Evidence finish(Evidence.Builder builder, String monitoredService) {
    String fingerprint =
        fingerprintService.logFingerprint(
            monitoredService, builder.observedAtValue(), builder.summaryValue());
    return builder.fingerprint(fingerprint).build();
  }
}
