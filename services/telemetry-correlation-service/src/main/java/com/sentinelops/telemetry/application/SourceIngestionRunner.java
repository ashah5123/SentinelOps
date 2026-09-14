package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;

/**
 * One backend adapter's incremental-polling behavior for a single monitored service. A runner
 * throwing must never prevent the other runners from running in the same polling cycle — see {@code
 * IngestionScheduler}'s per-source isolation.
 */
public interface SourceIngestionRunner {

  SourceSystem source();

  EvidenceType evidenceType();

  /** Runs one incremental polling cycle for {@code monitoredService}. May throw on failure. */
  IngestionCheckpointService.IngestionResult runFor(String monitoredService);
}
