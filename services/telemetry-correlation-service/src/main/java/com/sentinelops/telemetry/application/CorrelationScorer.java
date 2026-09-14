package com.sentinelops.telemetry.application;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Deterministic, rule-based evidence scoring — no machine-learning or LLM inference is involved.
 * Every rule that fires contributes a fixed, configured weight and is recorded in the returned
 * {@link ScoredEvidence#explanation()}, so a reviewer can see exactly which rules matched and why a
 * piece of evidence was surfaced. A high score reflects proximity and connection to the incident,
 * not a claim of causation — see the correlation-engine documentation.
 */
@Component
public class CorrelationScorer {

  public ScoredEvidence score(
      Evidence evidence,
      String affectedService,
      Instant detectedAt,
      Duration window,
      Set<String> seedTraceIds,
      Set<String> seedCorrelationIds,
      Set<String> connectedServices,
      boolean recentlyDeployed,
      TelemetryCorrelationProperties.Correlation.Weights weights) {
    double score = 0;
    StringBuilder explanation = new StringBuilder();

    if (affectedService.equals(evidence.getSourceService())) {
      score += weights.affectedServiceMatch();
      appendRule(explanation, "affected_service_match", weights.affectedServiceMatch());
    }

    double proximity = timeProximityFactor(evidence.getObservedAt(), detectedAt, window);
    if (proximity > 0) {
      double contribution = weights.timeProximity() * proximity;
      score += contribution;
      appendRule(explanation, "time_proximity", contribution);
    }

    if (evidence.getTraceId() != null && seedTraceIds.contains(evidence.getTraceId())) {
      score += weights.traceIdMatch();
      appendRule(explanation, "trace_id_match", weights.traceIdMatch());
    }

    if (evidence.getCorrelationId() != null
        && seedCorrelationIds.contains(evidence.getCorrelationId())) {
      score += weights.correlationIdMatch();
      appendRule(explanation, "correlation_id_match", weights.correlationIdMatch());
    }

    if (evidence.getEvidenceType() == EvidenceType.DEPLOYMENT
        && affectedService.equals(evidence.getSourceService())
        && recentlyDeployed) {
      score += weights.recentDeployment();
      appendRule(explanation, "recent_deployment", weights.recentDeployment());
    }

    if (connectedServices.contains(evidence.getSourceService())) {
      score += weights.dependencyConnection();
      appendRule(explanation, "dependency_connection", weights.dependencyConnection());
    }

    if (indicatesErrorOrFailure(evidence)) {
      score += weights.errorOrFailedStatus();
      appendRule(explanation, "error_or_failed_status", weights.errorOrFailedStatus());
    }

    if (indicatesElevatedMetric(evidence)) {
      score += weights.elevatedMetric();
      appendRule(explanation, "elevated_metric", weights.elevatedMetric());
    }

    return new ScoredEvidence(evidence, score, explanation.toString());
  }

  private double timeProximityFactor(Instant observedAt, Instant detectedAt, Duration window) {
    long windowSeconds = Math.max(1, window.toSeconds());
    long diffSeconds = Math.abs(Duration.between(detectedAt, observedAt).toSeconds());
    if (diffSeconds > windowSeconds) {
      return 0;
    }
    return 1.0 - ((double) diffSeconds / windowSeconds);
  }

  private boolean indicatesErrorOrFailure(Evidence evidence) {
    String severity = evidence.getSeverity();
    if (severity == null) {
      return false;
    }
    String normalized = severity.toUpperCase(Locale.ROOT);
    return normalized.contains("ERROR")
        || normalized.contains("FATAL")
        || normalized.contains("STATUS_CODE_ERROR");
  }

  private boolean indicatesElevatedMetric(Evidence evidence) {
    if (evidence.getEvidenceType() != EvidenceType.METRIC || evidence.getMetricValue() == null) {
      return false;
    }
    String metricName =
        evidence.getMetricName() == null ? "" : evidence.getMetricName().toLowerCase(Locale.ROOT);
    boolean isErrorOrLatencyMetric = metricName.contains("error") || metricName.contains("latency");
    return isErrorOrLatencyMetric && evidence.getMetricValue() > 0;
  }

  private void appendRule(StringBuilder explanation, String ruleName, double contribution) {
    if (!explanation.isEmpty()) {
      explanation.append("; ");
    }
    explanation
        .append(ruleName)
        .append("(+")
        .append(Math.round(contribution * 100.0) / 100.0)
        .append(")");
  }

  /** One evidence record's score and human-readable rule explanation. */
  public record ScoredEvidence(Evidence evidence, double score, String explanation) {}
}
