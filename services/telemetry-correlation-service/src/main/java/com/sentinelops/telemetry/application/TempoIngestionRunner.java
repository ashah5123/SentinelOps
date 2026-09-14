package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.adapters.tempo.TempoClient;
import com.sentinelops.telemetry.adapters.tempo.TempoNormalizer;
import com.sentinelops.telemetry.adapters.tempo.TempoSearchResponse;
import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Incremental Tempo ingestion: searches for traces rooted at one monitored service over the window
 * since its last checkpoint (coarse, root-trace-level evidence — see {@code TempoNormalizer}),
 * normalizes and deduplicates the results, and advances the checkpoint. Span-level detail for a
 * specific trace is fetched on demand instead (see {@code TraceLookupService}), not during routine
 * polling, to keep per-cycle volume bounded.
 */
@Component
public class TempoIngestionRunner implements SourceIngestionRunner {

  private final TempoClient client;
  private final TempoNormalizer normalizer;
  private final IngestionCheckpointService checkpointService;
  private final FingerprintService fingerprintService;
  private final TelemetryCorrelationProperties properties;

  public TempoIngestionRunner(
      TempoClient client,
      TempoNormalizer normalizer,
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
    return SourceSystem.TEMPO;
  }

  @Override
  public EvidenceType evidenceType() {
    return EvidenceType.TRACE;
  }

  @Override
  public IngestionCheckpointService.IngestionResult runFor(String monitoredService) {
    TelemetryCorrelationProperties.Ingestion ingestion = properties.ingestion();
    Instant now = Instant.now();
    Instant watermark =
        checkpointService.currentWatermark(
            source(), monitoredService, now.minus(ingestion.queryWindow()));
    Instant from = watermark.minus(ingestion.overlapWindow());

    TempoSearchResponse response = client.search(monitoredService, from, now);

    List<Evidence> candidates =
        normalizer.normalizeSearchResults(monitoredService, response).stream()
            .map(builder -> finish(builder, monitoredService))
            .toList();

    return checkpointService.persistAndAdvance(
        source(), monitoredService, now, candidates, ingestion.maxRecordsPerCycle());
  }

  private Evidence finish(Evidence.Builder builder, String monitoredService) {
    String fingerprint =
        fingerprintService.traceFingerprint(builder.traceIdValue(), builder.spanIdValue());
    return builder.fingerprint(fingerprint).build();
  }
}
