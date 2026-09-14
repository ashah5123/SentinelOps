package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.CorrelationEvidence;
import com.sentinelops.telemetry.domain.CorrelationResult;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.events.EventTypes;
import com.sentinelops.telemetry.events.EvidenceCorrelatedPayload;
import com.sentinelops.telemetry.events.IncidentDetectedPayload;
import com.sentinelops.telemetry.infrastructure.persistence.CorrelationEvidenceRepository;
import com.sentinelops.telemetry.infrastructure.persistence.CorrelationResultRepository;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import com.sentinelops.telemetry.observability.Spans;
import com.sentinelops.telemetry.observability.TelemetryMetrics;
import io.micrometer.core.instrument.Timer;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic, rule-based correlation of telemetry evidence to a detected incident — no
 * machine-learning or LLM inference. A correlation score reflects time proximity, service
 * connection, and matching identifiers between evidence and the incident; it is never presented as
 * a confirmed root cause (see {@code docs/decisions} for this phase's ADR and {@code
 * CorrelationScorer} for the scoring rules themselves).
 */
@Service
public class CorrelationEngine {

  private static final Logger log = LoggerFactory.getLogger(CorrelationEngine.class);
  private static final int MAX_CANDIDATE_EVIDENCE = 500;
  private static final String OUTBOX_AGGREGATE_TYPE = "CorrelationResult";

  private final EvidenceRepository evidenceRepository;
  private final CorrelationResultRepository correlationResultRepository;
  private final CorrelationEvidenceRepository correlationEvidenceRepository;
  private final DeploymentService deploymentService;
  private final DependencyGraphService dependencyGraphService;
  private final CorrelationScorer scorer;
  private final OutboxWriter outboxWriter;
  private final TelemetryCorrelationProperties properties;
  private final TelemetryMetrics metrics;
  private final Spans spans;

  public CorrelationEngine(
      EvidenceRepository evidenceRepository,
      CorrelationResultRepository correlationResultRepository,
      CorrelationEvidenceRepository correlationEvidenceRepository,
      DeploymentService deploymentService,
      DependencyGraphService dependencyGraphService,
      CorrelationScorer scorer,
      OutboxWriter outboxWriter,
      TelemetryCorrelationProperties properties,
      TelemetryMetrics metrics,
      Spans spans) {
    this.evidenceRepository = evidenceRepository;
    this.correlationResultRepository = correlationResultRepository;
    this.correlationEvidenceRepository = correlationEvidenceRepository;
    this.deploymentService = deploymentService;
    this.dependencyGraphService = dependencyGraphService;
    this.scorer = scorer;
    this.outboxWriter = outboxWriter;
    this.properties = properties;
    this.metrics = metrics;
    this.spans = spans;
  }

  @Transactional
  public void correlate(
      IncidentDetectedPayload payload, String sourceEventId, String correlationId) {
    if (correlationResultRepository.existsBySourceEventId(sourceEventId)) {
      log.info(
          "Ignoring duplicate incident.detected.v1 event eventId={} correlationId={}",
          sourceEventId,
          correlationId);
      metrics.incidentCorrelated("duplicate");
      return;
    }

    Timer.Sample timer = metrics.startCorrelationTimer();
    try {
      spans.inSpan(
          "correlation.evaluate",
          Map.of(),
          () -> runCorrelation(payload, sourceEventId, correlationId));
      metrics.incidentCorrelated("correlated");
    } catch (RuntimeException e) {
      metrics.incidentCorrelated("failed");
      throw e;
    } finally {
      metrics.stopCorrelationTimer(timer);
    }
  }

  private void runCorrelation(
      IncidentDetectedPayload payload, String sourceEventId, String correlationId) {
    TelemetryCorrelationProperties.Correlation config = properties.correlation();
    String affectedService = payload.affectedService();
    Instant detectedAt = payload.detectedAt();
    Instant windowStart = detectedAt.minus(config.window());
    Instant windowEnd = detectedAt.plus(config.window());

    Set<String> connectedServices =
        dependencyGraphService.connectedServices(affectedService, config.maxDependencyDepth());

    List<Evidence> candidates = new java.util.ArrayList<>();
    candidates.addAll(evidenceInWindow(affectedService, windowStart, windowEnd));
    for (String connected : connectedServices) {
      candidates.addAll(evidenceInWindow(connected, windowStart, windowEnd));
    }

    Set<String> seedTraceIds = new LinkedHashSet<>();
    Set<String> seedCorrelationIds = new LinkedHashSet<>();
    for (Evidence evidence : candidates) {
      if (affectedService.equals(evidence.getSourceService())) {
        if (evidence.getTraceId() != null) {
          seedTraceIds.add(evidence.getTraceId());
        }
        if (evidence.getCorrelationId() != null) {
          seedCorrelationIds.add(evidence.getCorrelationId());
        }
      }
    }
    for (String traceId : Set.copyOf(seedTraceIds)) {
      candidates.addAll(evidenceRepository.findByTraceId(traceId));
    }
    for (String corrId : Set.copyOf(seedCorrelationIds)) {
      candidates.addAll(evidenceRepository.findByCorrelationId(corrId));
    }

    boolean recentlyDeployed =
        !deploymentService.recentDeployments(affectedService, windowStart, detectedAt).isEmpty();

    Map<java.util.UUID, Evidence> deduplicated = new java.util.LinkedHashMap<>();
    for (Evidence evidence : candidates) {
      deduplicated.putIfAbsent(evidence.getId(), evidence);
    }

    List<CorrelationScorer.ScoredEvidence> scored =
        deduplicated.values().stream()
            .map(
                evidence ->
                    scorer.score(
                        evidence,
                        affectedService,
                        detectedAt,
                        config.window(),
                        seedTraceIds,
                        seedCorrelationIds,
                        connectedServices,
                        recentlyDeployed,
                        config.weights()))
            .filter(s -> s.score() > 0)
            .sorted(Comparator.comparingDouble(CorrelationScorer.ScoredEvidence::score).reversed())
            .limit(config.maxEvidenceResults())
            .toList();

    CorrelationResult result =
        new CorrelationResult(
            payload.incidentId(),
            affectedService,
            detectedAt,
            scored.size(),
            sourceEventId,
            correlationId);
    correlationResultRepository.save(result);

    int rank = 0;
    for (CorrelationScorer.ScoredEvidence s : scored) {
      rank++;
      correlationEvidenceRepository.save(
          new CorrelationEvidence(
              result.getId(), s.evidence().getId(), s.score(), s.explanation(), rank));
      metrics.evidenceSelected(s.evidence().getEvidenceType().name());
      publishEvidence(payload, s, correlationId);
    }

    log.info(
        "Correlated incident {} ({} evidence records) correlationId={}",
        payload.incidentId(),
        scored.size(),
        correlationId);
  }

  private List<Evidence> evidenceInWindow(String service, Instant from, Instant to) {
    return evidenceRepository
        .findBySourceServiceAndObservedAtBetween(
            service,
            from,
            to,
            PageRequest.of(0, MAX_CANDIDATE_EVIDENCE, Sort.by("observedAt").descending()))
        .getContent();
  }

  private void publishEvidence(
      IncidentDetectedPayload payload,
      CorrelationScorer.ScoredEvidence scored,
      String correlationId) {
    Evidence evidence = scored.evidence();
    EvidenceCorrelatedPayload evidencePayload =
        new EvidenceCorrelatedPayload(
            payload.incidentId(),
            evidence.getId(),
            evidence.getEvidenceType().name(),
            evidence.getSummary(),
            evidence.getSourceService(),
            evidence.getObservedAt(),
            evidence.getSourceReference(),
            scored.score(),
            scored.explanation(),
            evidence.getTraceId(),
            evidence.getDeploymentId());
    outboxWriter.append(
        OUTBOX_AGGREGATE_TYPE,
        payload.incidentId(),
        EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1,
        EventTypes.INCIDENT_EVIDENCE_CORRELATED_SCHEMA_VERSION,
        evidencePayload,
        correlationId);
  }
}
