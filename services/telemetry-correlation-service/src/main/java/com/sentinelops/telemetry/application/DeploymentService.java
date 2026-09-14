package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.domain.Deployment;
import com.sentinelops.telemetry.domain.DeploymentStatus;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import com.sentinelops.telemetry.events.DeploymentChangedPayload;
import com.sentinelops.telemetry.infrastructure.persistence.DeploymentRepository;
import com.sentinelops.telemetry.infrastructure.persistence.EvidenceRepository;
import com.sentinelops.telemetry.observability.TelemetryMetrics;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code deployment.changed.v1} idempotently (keyed by the envelope's {@code eventId}) and
 * makes deployment history queryable by service and time range. Every recorded deployment also
 * creates a matching {@link Evidence} row (type {@link EvidenceType#DEPLOYMENT}) so correlation can
 * find it alongside metrics, logs, and traces.
 */
@Service
public class DeploymentService {

  private static final Logger log = LoggerFactory.getLogger(DeploymentService.class);

  private final DeploymentRepository deploymentRepository;
  private final EvidenceRepository evidenceRepository;
  private final FingerprintService fingerprintService;
  private final TelemetryMetrics metrics;

  public DeploymentService(
      DeploymentRepository deploymentRepository,
      EvidenceRepository evidenceRepository,
      FingerprintService fingerprintService,
      TelemetryMetrics metrics) {
    this.deploymentRepository = deploymentRepository;
    this.evidenceRepository = evidenceRepository;
    this.fingerprintService = fingerprintService;
    this.metrics = metrics;
  }

  @Transactional
  public void recordDeployment(
      DeploymentChangedPayload payload, String sourceEventId, String correlationId) {
    if (deploymentRepository.existsBySourceEventId(sourceEventId)) {
      log.info(
          "Ignoring duplicate deployment event eventId={} correlationId={}",
          sourceEventId,
          correlationId);
      metrics.deploymentEventProcessed("duplicate");
      return;
    }

    DeploymentStatus status = DeploymentStatus.valueOf(payload.status());
    Deployment deployment =
        Deployment.record(
            payload.deploymentId(),
            payload.serviceName(),
            payload.version(),
            payload.environment(),
            status,
            payload.startedAt(),
            payload.completedAt(),
            payload.source(),
            payload.rollbackOfDeploymentId(),
            sourceEventId,
            correlationId);
    deploymentRepository.save(deployment);

    String fingerprint = fingerprintService.deploymentFingerprint(sourceEventId);
    if (!evidenceRepository.existsByFingerprint(fingerprint)) {
      Evidence evidence =
          Evidence.builder(
                  EvidenceType.DEPLOYMENT, SourceSystem.DEPLOYMENT_EVENT, payload.serviceName())
              .observedAt(payload.startedAt())
              .deploymentId(deployment.getId())
              .correlationId(correlationId)
              .summary(
                  truncate(
                      "Deployment %s of %s to %s (%s)"
                          .formatted(
                              payload.version(),
                              payload.serviceName(),
                              payload.environment(),
                              status),
                      500))
              .sourceReference("deployment:" + payload.deploymentId())
              .fingerprint(fingerprint)
              .build();
      evidenceRepository.save(evidence);
    }

    metrics.deploymentEventProcessed("processed");
    log.info(
        "Recorded deployment {} for service={} status={} correlationId={}",
        payload.deploymentId(),
        payload.serviceName(),
        status,
        correlationId);
  }

  public Page<Deployment> findByServiceAndRange(
      String serviceName, Instant from, Instant to, Pageable pageable) {
    return deploymentRepository.findByServiceNameAndStartedAtBetween(
        serviceName, from, to, pageable);
  }

  public List<Deployment> recentDeployments(String serviceName, Instant from, Instant to) {
    return deploymentRepository.findByServiceNameAndStartedAtBetweenOrderByStartedAtDesc(
        serviceName, from, to);
  }

  private String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
