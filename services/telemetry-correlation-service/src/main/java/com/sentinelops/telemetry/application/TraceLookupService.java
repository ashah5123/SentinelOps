package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.adapters.tempo.TempoClient;
import com.sentinelops.telemetry.adapters.tempo.TempoNormalizer;
import com.sentinelops.telemetry.adapters.tempo.TempoQueryException;
import com.sentinelops.telemetry.adapters.tempo.TempoTraceResponse;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * On-demand, span-level trace lookup — used by the correlation engine to fetch full span detail for
 * a trace ID it found in Loki logs or in an incident's own telemetry, rather than waiting for the
 * next Tempo polling cycle (see {@code TempoIngestionRunner}, which only ingests coarse,
 * root-trace-level evidence on a schedule). A failed lookup degrades gracefully: the caller
 * proceeds with whatever evidence it already has.
 */
@Service
public class TraceLookupService {

  private static final Logger log = LoggerFactory.getLogger(TraceLookupService.class);

  private final TempoClient tempoClient;
  private final TempoNormalizer normalizer;
  private final FingerprintService fingerprintService;
  private final EvidenceRepository evidenceRepository;

  public TraceLookupService(
      TempoClient tempoClient,
      TempoNormalizer normalizer,
      FingerprintService fingerprintService,
      EvidenceRepository evidenceRepository) {
    this.tempoClient = tempoClient;
    this.normalizer = normalizer;
    this.fingerprintService = fingerprintService;
    this.evidenceRepository = evidenceRepository;
  }

  /** Fetches, normalizes, and persists (deduplicated) span-level evidence for one trace ID. */
  @Transactional
  public List<Evidence> lookupAndPersist(String fallbackService, String traceId) {
    TempoTraceResponse response;
    try {
      response = tempoClient.getTrace(traceId);
    } catch (TempoQueryException e) {
      log.warn("Trace lookup failed for traceId={}: {}", traceId, e.getMessage());
      return List.of();
    }

    return normalizer.normalizeTrace(fallbackService, traceId, response).stream()
        .map(
            builder ->
                builder
                    .fingerprint(
                        fingerprintService.traceFingerprint(traceId, builder.spanIdValue()))
                    .build())
        .map(this::saveIfNew)
        .flatMap(java.util.Optional::stream)
        .toList();
  }

  private java.util.Optional<Evidence> saveIfNew(Evidence evidence) {
    if (evidenceRepository.existsByFingerprint(evidence.getFingerprint())) {
      return evidenceRepository.findByFingerprint(evidence.getFingerprint());
    }
    try {
      return java.util.Optional.of(evidenceRepository.saveAndFlush(evidence));
    } catch (DataIntegrityViolationException e) {
      return evidenceRepository.findByFingerprint(evidence.getFingerprint());
    }
  }
}
