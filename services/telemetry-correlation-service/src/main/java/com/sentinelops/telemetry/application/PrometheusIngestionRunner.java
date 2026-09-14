package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.adapters.prometheus.PrometheusClient;
import com.sentinelops.telemetry.adapters.prometheus.PrometheusNormalizer;
import com.sentinelops.telemetry.adapters.prometheus.PrometheusResponse;
import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Incremental Prometheus ingestion: runs every configured PromQL query (see {@code
 * sentinelops.telemetry.prometheus-queries}) for one monitored service over the window since its
 * last checkpoint, normalizes and deduplicates the results, and advances the checkpoint.
 */
@Component
public class PrometheusIngestionRunner implements SourceIngestionRunner {

  private final PrometheusClient client;
  private final PrometheusNormalizer normalizer;
  private final IngestionCheckpointService checkpointService;
  private final FingerprintService fingerprintService;
  private final TelemetryCorrelationProperties properties;

  public PrometheusIngestionRunner(
      PrometheusClient client,
      PrometheusNormalizer normalizer,
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
    return SourceSystem.PROMETHEUS;
  }

  @Override
  public EvidenceType evidenceType() {
    return EvidenceType.METRIC;
  }

  @Override
  public IngestionCheckpointService.IngestionResult runFor(String monitoredService) {
    TelemetryCorrelationProperties.Ingestion ingestion = properties.ingestion();
    Instant now = Instant.now();
    Instant watermark =
        checkpointService.currentWatermark(
            source(), monitoredService, now.minus(ingestion.queryWindow()));
    Instant from = watermark.minus(ingestion.overlapWindow());

    List<Evidence> candidates =
        properties.prometheusQueries().entrySet().stream()
            .flatMap(
                entry -> {
                  String promQuery = entry.getValue().formatted(monitoredService);
                  PrometheusResponse response = client.queryRange(promQuery, from, now);
                  return normalizer.normalize(promQuery, monitoredService, response).stream();
                })
            .map(builder -> finish(builder, monitoredService))
            .toList();

    return checkpointService.persistAndAdvance(
        source(), monitoredService, now, candidates, ingestion.maxRecordsPerCycle());
  }

  private Evidence finish(Evidence.Builder builder, String monitoredService) {
    String fingerprint =
        fingerprintService.metricFingerprint(
            monitoredService,
            builder.metricNameValue(),
            builder.observedAtValue(),
            builder.attributesValue());
    return builder.fingerprint(fingerprint).build();
  }
}
