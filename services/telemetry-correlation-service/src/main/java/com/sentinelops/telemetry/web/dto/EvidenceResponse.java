package com.sentinelops.telemetry.web.dto;

import com.sentinelops.telemetry.domain.Evidence;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** API projection of {@link Evidence} — never the JPA entity itself. */
public record EvidenceResponse(
    UUID id,
    String evidenceType,
    String sourceSystem,
    String sourceService,
    Instant observedAt,
    Instant ingestedAt,
    String traceId,
    String spanId,
    String correlationId,
    UUID deploymentId,
    String metricName,
    Double metricValue,
    String severity,
    String summary,
    String sourceReference,
    Map<String, String> attributes) {

  public static EvidenceResponse from(Evidence evidence) {
    return new EvidenceResponse(
        evidence.getId(),
        evidence.getEvidenceType().name(),
        evidence.getSourceSystem().name(),
        evidence.getSourceService(),
        evidence.getObservedAt(),
        evidence.getIngestedAt(),
        evidence.getTraceId(),
        evidence.getSpanId(),
        evidence.getCorrelationId(),
        evidence.getDeploymentId(),
        evidence.getMetricName(),
        evidence.getMetricValue(),
        evidence.getSeverity(),
        evidence.getSummary(),
        evidence.getSourceReference(),
        evidence.getAttributes());
  }
}
