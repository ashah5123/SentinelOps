package com.sentinelops.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinelops.telemetry.domain.ServiceDependency;
import com.sentinelops.telemetry.events.ServiceDependencyChangedPayload;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import com.sentinelops.telemetry.infrastructure.persistence.ServiceDependencyHistoryRepository;
import com.sentinelops.telemetry.infrastructure.persistence.ServiceDependencyRepository;
import com.sentinelops.telemetry.observability.TelemetryMetrics;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DependencyGraphServiceTest {

  @Mock private ServiceDependencyRepository dependencyRepository;
  @Mock private ServiceDependencyHistoryRepository historyRepository;
  @Mock private EvidenceRepository evidenceRepository;
  @Mock private TelemetryMetrics metrics;

  private DependencyGraphService service;

  @BeforeEach
  void setUp() {
    service =
        new DependencyGraphService(
            dependencyRepository,
            historyRepository,
            evidenceRepository,
            new FingerprintService(),
            metrics);
    lenient().when(evidenceRepository.existsByFingerprint(any())).thenReturn(false);
  }

  @Test
  void rejectsAServiceDependingOnItself() {
    ServiceDependencyChangedPayload payload =
        new ServiceDependencyChangedPayload(
            "svc-a", "svc-a", "HTTP", "local", "ADDED", Instant.now());

    assertThatThrownBy(() -> service.applyChange(payload, "event-1", "corr-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot depend on itself");
  }

  @Test
  void duplicateEventIsIgnoredIdempotently() {
    when(historyRepository.existsBySourceEventId("event-1")).thenReturn(true);
    ServiceDependencyChangedPayload payload =
        new ServiceDependencyChangedPayload(
            "svc-a", "svc-b", "HTTP", "local", "ADDED", Instant.now());

    service.applyChange(payload, "event-1", "corr-1");

    verify(dependencyRepository, never()).save(any());
    verify(historyRepository, never()).save(any());
  }

  @Test
  void addingANewEdgeCreatesTheDependencyAndHistoryRow() {
    when(historyRepository.existsBySourceEventId("event-2")).thenReturn(false);
    when(dependencyRepository.findBySourceServiceAndTargetServiceAndDependencyTypeAndEnvironment(
            "svc-a", "svc-b", "HTTP", "local"))
        .thenReturn(Optional.empty());
    ServiceDependencyChangedPayload payload =
        new ServiceDependencyChangedPayload(
            "svc-a", "svc-b", "HTTP", "local", "ADDED", Instant.now());

    service.applyChange(payload, "event-2", "corr-2");

    verify(dependencyRepository).save(any(ServiceDependency.class));
    verify(historyRepository).save(any());
  }

  @Test
  void removingANonexistentEdgeIsADeterministicNoOp() {
    when(historyRepository.existsBySourceEventId("event-3")).thenReturn(false);
    when(dependencyRepository.findBySourceServiceAndTargetServiceAndDependencyTypeAndEnvironment(
            "svc-a", "svc-b", "HTTP", "local"))
        .thenReturn(Optional.empty());
    ServiceDependencyChangedPayload payload =
        new ServiceDependencyChangedPayload(
            "svc-a", "svc-b", "HTTP", "local", "REMOVED", Instant.now());

    service.applyChange(payload, "event-3", "corr-3");

    verify(dependencyRepository, never()).delete(any());
    // The history row is still recorded — removal of an already-absent edge is a no-op on the
    // graph itself, but the event is still durably recorded as having been applied.
    verify(historyRepository).save(any());
  }

  @Test
  void cyclesAreAllowedAsValidRuntimeTopology() {
    when(historyRepository.existsBySourceEventId(any())).thenReturn(false);
    when(dependencyRepository.findBySourceServiceAndTargetServiceAndDependencyTypeAndEnvironment(
            any(), any(), any(), any()))
        .thenReturn(Optional.empty());

    service.applyChange(
        new ServiceDependencyChangedPayload(
            "svc-a", "svc-b", "HTTP", "local", "ADDED", Instant.now()),
        "event-4",
        "corr-4");
    service.applyChange(
        new ServiceDependencyChangedPayload(
            "svc-b", "svc-a", "HTTP", "local", "ADDED", Instant.now()),
        "event-5",
        "corr-5");

    verify(dependencyRepository, org.mockito.Mockito.times(2)).save(any());
  }

  @Test
  void connectedServicesTraversesOneHopByDefault() {
    ServiceDependency edge =
        new ServiceDependency("incident-service", "postgres", "DATABASE", "local", Instant.now());
    when(dependencyRepository.findBySourceServiceOrTargetService(
            "incident-service", "incident-service"))
        .thenReturn(List.of(edge));

    var connected = service.connectedServices("incident-service", 1);

    assertThat(connected).containsExactly("postgres");
  }
}
