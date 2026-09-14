package com.sentinelops.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.IngestionCheckpoint;
import com.sentinelops.telemetry.domain.SourceSystem;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import com.sentinelops.telemetry.infrastructure.persistence.IngestionCheckpointRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IngestionCheckpointServiceTest {

  @Mock private IngestionCheckpointRepository checkpointRepository;
  @Mock private EvidenceRepository evidenceRepository;

  private IngestionCheckpointService service;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    service = new IngestionCheckpointService(checkpointRepository, evidenceRepository);
  }

  @Test
  void currentWatermarkCreatesACheckpointWhenNoneExistsYet() {
    Instant defaultStart = Instant.parse("2026-01-01T00:00:00Z");
    when(checkpointRepository.findBySourceAndMonitoredService(SourceSystem.PROMETHEUS, "svc"))
        .thenReturn(Optional.empty());

    Instant watermark = service.currentWatermark(SourceSystem.PROMETHEUS, "svc", defaultStart);

    assertThat(watermark).isEqualTo(defaultStart);
    verify(checkpointRepository).save(any(IngestionCheckpoint.class));
  }

  @Test
  void currentWatermarkReturnsTheExistingCheckpointWithoutCreatingANewOne() {
    Instant existingWatermark = Instant.parse("2026-01-01T01:00:00Z");
    IngestionCheckpoint existing =
        new IngestionCheckpoint(SourceSystem.LOKI, "svc", existingWatermark);
    when(checkpointRepository.findBySourceAndMonitoredService(SourceSystem.LOKI, "svc"))
        .thenReturn(Optional.of(existing));

    Instant watermark = service.currentWatermark(SourceSystem.LOKI, "svc", Instant.EPOCH);

    assertThat(watermark).isEqualTo(existingWatermark);
    verify(checkpointRepository, never()).save(any());
  }

  @Test
  void persistAndAdvanceSkipsRecordsThatAlreadyExistByFingerprint() {
    Evidence duplicate = evidence("dup-fingerprint");
    when(evidenceRepository.existsByFingerprint("dup-fingerprint")).thenReturn(true);
    when(checkpointRepository.findBySourceAndMonitoredService(SourceSystem.PROMETHEUS, "svc"))
        .thenReturn(
            Optional.of(new IngestionCheckpoint(SourceSystem.PROMETHEUS, "svc", Instant.EPOCH)));

    IngestionCheckpointService.IngestionResult result =
        service.persistAndAdvance(
            SourceSystem.PROMETHEUS, "svc", Instant.now(), List.of(duplicate), 100);

    assertThat(result.ingested()).isZero();
    assertThat(result.duplicates()).isEqualTo(1);
    verify(evidenceRepository, never()).saveAndFlush(any());
  }

  @Test
  void persistAndAdvanceStopsAtTheConfiguredMaxRecords() {
    when(evidenceRepository.existsByFingerprint(any())).thenReturn(false);
    when(checkpointRepository.findBySourceAndMonitoredService(SourceSystem.PROMETHEUS, "svc"))
        .thenReturn(
            Optional.of(new IngestionCheckpoint(SourceSystem.PROMETHEUS, "svc", Instant.EPOCH)));

    List<Evidence> candidates = List.of(evidence("fp1"), evidence("fp2"), evidence("fp3"));

    IngestionCheckpointService.IngestionResult result =
        service.persistAndAdvance(SourceSystem.PROMETHEUS, "svc", Instant.now(), candidates, 2);

    assertThat(result.ingested()).isEqualTo(2);
  }

  @Test
  void persistAndAdvanceMovesTheWatermarkForwardOnSuccess() {
    IngestionCheckpoint checkpoint =
        new IngestionCheckpoint(SourceSystem.TEMPO, "svc", Instant.EPOCH);
    when(checkpointRepository.findBySourceAndMonitoredService(SourceSystem.TEMPO, "svc"))
        .thenReturn(Optional.of(checkpoint));
    Instant newWindowEnd = Instant.parse("2026-01-01T02:00:00Z");

    service.persistAndAdvance(SourceSystem.TEMPO, "svc", newWindowEnd, List.of(), 10);

    assertThat(checkpoint.getWatermark()).isEqualTo(newWindowEnd);
    assertThat(checkpoint.getLastRunStatus()).isEqualTo("SUCCESS");
  }

  @Test
  void recordFailureMarksTheCheckpointFailedWithoutMovingTheWatermark() {
    Instant originalWatermark = Instant.parse("2026-01-01T00:00:00Z");
    IngestionCheckpoint checkpoint =
        new IngestionCheckpoint(SourceSystem.LOKI, "svc", originalWatermark);
    when(checkpointRepository.findBySourceAndMonitoredService(SourceSystem.LOKI, "svc"))
        .thenReturn(Optional.of(checkpoint));

    service.recordFailure(SourceSystem.LOKI, "svc");

    assertThat(checkpoint.getWatermark()).isEqualTo(originalWatermark);
    assertThat(checkpoint.getLastRunStatus()).isEqualTo("FAILED");
  }

  private Evidence evidence(String fingerprint) {
    return Evidence.builder(EvidenceType.METRIC, SourceSystem.PROMETHEUS, "svc")
        .observedAt(Instant.now())
        .summary("s")
        .sourceReference("r")
        .fingerprint(fingerprint)
        .build();
  }
}
