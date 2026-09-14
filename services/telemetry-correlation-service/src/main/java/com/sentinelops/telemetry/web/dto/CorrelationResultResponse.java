package com.sentinelops.telemetry.web.dto;

import com.sentinelops.telemetry.domain.CorrelationEvidence;
import com.sentinelops.telemetry.domain.CorrelationResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * API projection of one {@link CorrelationResult} and the evidence it selected. {@code
 * correlationScore} reflects rule-based proximity and connection to the incident — it is not a
 * confirmed root cause.
 */
public record CorrelationResultResponse(
    UUID id,
    UUID incidentId,
    String affectedService,
    Instant detectedAt,
    Instant evaluatedAt,
    int evidenceCount,
    List<ScoredEvidenceResponse> evidence) {

  public record ScoredEvidenceResponse(
      UUID evidenceId, double score, String explanation, int rank) {

    public static ScoredEvidenceResponse from(CorrelationEvidence evidence) {
      return new ScoredEvidenceResponse(
          evidence.getEvidenceId(),
          evidence.getScore(),
          evidence.getExplanation(),
          evidence.getRank());
    }
  }

  public static CorrelationResultResponse from(
      CorrelationResult result, List<CorrelationEvidence> evidence) {
    return new CorrelationResultResponse(
        result.getId(),
        result.getIncidentId(),
        result.getAffectedService(),
        result.getDetectedAt(),
        result.getEvaluatedAt(),
        result.getEvidenceCount(),
        evidence.stream().map(ScoredEvidenceResponse::from).toList());
  }
}
