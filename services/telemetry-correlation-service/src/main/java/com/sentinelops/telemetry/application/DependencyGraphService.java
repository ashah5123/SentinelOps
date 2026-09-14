package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.domain.DependencyOperation;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.ServiceDependency;
import com.sentinelops.telemetry.domain.ServiceDependencyHistory;
import com.sentinelops.telemetry.domain.SourceSystem;
import com.sentinelops.telemetry.events.ServiceDependencyChangedPayload;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import com.sentinelops.telemetry.infrastructure.persistence.ServiceDependencyHistoryRepository;
import com.sentinelops.telemetry.infrastructure.persistence.ServiceDependencyRepository;
import com.sentinelops.telemetry.observability.TelemetryMetrics;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintains the current service-dependency graph, consuming {@code service.dependency.changed.v1}
 * idempotently (keyed by the envelope's {@code eventId}) while preserving full change history in
 * {@link ServiceDependencyHistory}.
 *
 * <p>A dependency cycle (A depends on B, B depends on A) is a valid runtime topology — e.g. two
 * services calling each other over different endpoints — and is accepted; only a service depending
 * on itself is rejected (see {@link #validate}).
 */
@Service
public class DependencyGraphService {

  private static final Logger log = LoggerFactory.getLogger(DependencyGraphService.class);

  private final ServiceDependencyRepository dependencyRepository;
  private final ServiceDependencyHistoryRepository historyRepository;
  private final EvidenceRepository evidenceRepository;
  private final FingerprintService fingerprintService;
  private final TelemetryMetrics metrics;

  public DependencyGraphService(
      ServiceDependencyRepository dependencyRepository,
      ServiceDependencyHistoryRepository historyRepository,
      EvidenceRepository evidenceRepository,
      FingerprintService fingerprintService,
      TelemetryMetrics metrics) {
    this.dependencyRepository = dependencyRepository;
    this.historyRepository = historyRepository;
    this.evidenceRepository = evidenceRepository;
    this.fingerprintService = fingerprintService;
    this.metrics = metrics;
  }

  @Transactional
  public void applyChange(
      ServiceDependencyChangedPayload payload, String sourceEventId, String correlationId) {
    if (historyRepository.existsBySourceEventId(sourceEventId)) {
      log.info(
          "Ignoring duplicate dependency event eventId={} correlationId={}",
          sourceEventId,
          correlationId);
      metrics.dependencyEventProcessed(payload.operation().toLowerCase(), "duplicate");
      return;
    }
    validate(payload);

    DependencyOperation operation = DependencyOperation.valueOf(payload.operation());
    Optional<ServiceDependency> existing =
        dependencyRepository.findBySourceServiceAndTargetServiceAndDependencyTypeAndEnvironment(
            payload.sourceService(),
            payload.targetService(),
            payload.dependencyType(),
            payload.environment());

    switch (operation) {
      case ADDED, UPDATED -> {
        if (existing.isPresent()) {
          existing.get().touch(payload.effectiveAt());
          dependencyRepository.save(existing.get());
        } else {
          dependencyRepository.save(
              new ServiceDependency(
                  payload.sourceService(),
                  payload.targetService(),
                  payload.dependencyType(),
                  payload.environment(),
                  payload.effectiveAt()));
        }
      }
      case REMOVED -> existing.ifPresent(dependencyRepository::delete);
        // A REMOVED event for an edge that doesn't currently exist is a deterministic no-op —
        // the graph already reflects the desired end state.
    }

    historyRepository.save(
        new ServiceDependencyHistory(
            payload.sourceService(),
            payload.targetService(),
            payload.dependencyType(),
            payload.environment(),
            operation,
            payload.effectiveAt(),
            sourceEventId));

    recordEvidence(payload, sourceEventId, correlationId, operation);
    metrics.dependencyEventProcessed(payload.operation().toLowerCase(), "processed");
    log.info(
        "Applied dependency change {} {} -> {} ({}) correlationId={}",
        operation,
        payload.sourceService(),
        payload.targetService(),
        payload.dependencyType(),
        correlationId);
  }

  private void recordEvidence(
      ServiceDependencyChangedPayload payload,
      String sourceEventId,
      String correlationId,
      DependencyOperation operation) {
    String fingerprint = fingerprintService.dependencyFingerprint(sourceEventId);
    if (evidenceRepository.existsByFingerprint(fingerprint)) {
      return;
    }
    Evidence evidence =
        Evidence.builder(
                EvidenceType.DEPENDENCY, SourceSystem.DEPENDENCY_EVENT, payload.sourceService())
            .observedAt(payload.effectiveAt())
            .correlationId(correlationId)
            .summary(
                "%s dependency %s -> %s (%s)"
                    .formatted(
                        operation,
                        payload.sourceService(),
                        payload.targetService(),
                        payload.dependencyType()))
            .sourceReference("dependency-event:" + sourceEventId)
            .fingerprint(fingerprint)
            .build();
    evidenceRepository.save(evidence);
  }

  private void validate(ServiceDependencyChangedPayload payload) {
    if (payload.sourceService().equals(payload.targetService())) {
      throw new IllegalArgumentException(
          "A service cannot depend on itself: " + payload.sourceService());
    }
  }

  public List<ServiceDependency> currentGraph() {
    return dependencyRepository.findAllByOrderBySourceServiceAscTargetServiceAsc();
  }

  public List<ServiceDependencyHistory> historyFor(String serviceName) {
    return historyRepository.findBySourceServiceOrderByEffectiveAtDesc(serviceName);
  }

  /**
   * Breadth-first traversal of direct upstream (services that depend on {@code service}) and
   * downstream (services {@code service} depends on) neighbors, up to {@code maxDepth} hops. {@code
   * service} itself is never included in the result.
   */
  public Set<String> connectedServices(String service, int maxDepth) {
    Set<String> visited = new LinkedHashSet<>();
    ArrayDeque<String> frontier = new ArrayDeque<>();
    frontier.add(service);
    for (int depth = 0; depth < maxDepth && !frontier.isEmpty(); depth++) {
      ArrayDeque<String> nextFrontier = new ArrayDeque<>();
      for (String current : frontier) {
        for (ServiceDependency edge :
            dependencyRepository.findBySourceServiceOrTargetService(current, current)) {
          String neighbor =
              edge.getSourceService().equals(current)
                  ? edge.getTargetService()
                  : edge.getSourceService();
          if (!neighbor.equals(service) && visited.add(neighbor)) {
            nextFrontier.add(neighbor);
          }
        }
      }
      frontier = nextFrontier;
    }
    return visited;
  }
}
