package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.IngestionCheckpoint;
import com.sentinelops.telemetry.domain.SourceSystem;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import com.sentinelops.telemetry.infrastructure.persistence.IngestionCheckpointRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the read/advance lifecycle of {@link IngestionCheckpoint} together with the evidence a
 * polling cycle produced, in a single transaction — so a cycle that fails partway through
 * persistence never advances its watermark (see {@code docs/decisions} for this phase's ADR).
 * Deduplication relies on {@link Evidence#getFingerprint()}'s unique database constraint as the
 * authoritative guard, with a fast-path existence check to avoid unnecessary constraint-violation
 * round trips.
 */
@Service
public class IngestionCheckpointService {

  private static final Logger log = LoggerFactory.getLogger(IngestionCheckpointService.class);

  private final IngestionCheckpointRepository checkpointRepository;
  private final EvidenceRepository evidenceRepository;

  public IngestionCheckpointService(
      IngestionCheckpointRepository checkpointRepository, EvidenceRepository evidenceRepository) {
    this.checkpointRepository = checkpointRepository;
    this.evidenceRepository = evidenceRepository;
  }

  /**
   * Returns the current watermark for (source, monitoredService), creating a fresh checkpoint at
   * {@code defaultStart} if none exists yet.
   */
  @Transactional
  public Instant currentWatermark(
      SourceSystem source, String monitoredService, Instant defaultStart) {
    return checkpointRepository
        .findBySourceAndMonitoredService(source, monitoredService)
        .map(IngestionCheckpoint::getWatermark)
        .orElseGet(
            () -> {
              checkpointRepository.save(
                  new IngestionCheckpoint(source, monitoredService, defaultStart));
              return defaultStart;
            });
  }

  /**
   * Persists deduplicated evidence (bounded to {@code maxRecords}) and advances the checkpoint to
   * {@code windowEnd}, atomically. Returns the number of records actually ingested (excluding
   * duplicates). Never advances the checkpoint if this method throws.
   */
  @Transactional
  public IngestionResult persistAndAdvance(
      SourceSystem source,
      String monitoredService,
      Instant windowEnd,
      List<Evidence> candidates,
      int maxRecords) {
    int ingested = 0;
    int duplicates = 0;
    for (Evidence evidence : candidates) {
      if (ingested >= maxRecords) {
        break;
      }
      if (evidenceRepository.existsByFingerprint(evidence.getFingerprint())) {
        duplicates++;
        continue;
      }
      try {
        evidenceRepository.saveAndFlush(evidence);
        ingested++;
      } catch (DataIntegrityViolationException e) {
        // Lost a race with another writer on the same fingerprint — treat as a duplicate.
        duplicates++;
      }
    }

    IngestionCheckpoint checkpoint =
        checkpointRepository
            .findBySourceAndMonitoredService(source, monitoredService)
            .orElseGet(() -> new IngestionCheckpoint(source, monitoredService, windowEnd));
    checkpoint.advance(windowEnd);
    checkpointRepository.save(checkpoint);

    log.debug(
        "Ingestion cycle for source={} monitoredService={}: ingested={} duplicates={}",
        source,
        monitoredService,
        ingested,
        duplicates);
    return new IngestionResult(ingested, duplicates);
  }

  @Transactional
  public void recordFailure(SourceSystem source, String monitoredService) {
    checkpointRepository
        .findBySourceAndMonitoredService(source, monitoredService)
        .ifPresent(
            checkpoint -> {
              checkpoint.recordFailure();
              checkpointRepository.save(checkpoint);
            });
  }

  /** Outcome of one polling cycle's persistence step. */
  public record IngestionResult(int ingested, int duplicates) {}
}
