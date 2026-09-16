package com.sentinelops.incident.ai.web;

import com.sentinelops.incident.ai.AiSuggestion;
import java.time.Instant;
import java.util.UUID;

public record AiSuggestionResponse(
    UUID id,
    UUID incidentId,
    String providerName,
    String modelName,
    String status,
    String structuredResult,
    String suggestedSeverity,
    String suggestedCategory,
    String failureReason,
    Instant createdAt,
    String reviewStatus,
    String reviewedBy,
    Instant reviewedAt,
    String acceptedFields,
    String reviewFeedback) {

  public static AiSuggestionResponse from(AiSuggestion s) {
    return new AiSuggestionResponse(
        s.id(),
        s.incidentId(),
        s.providerName(),
        s.modelName(),
        s.status().name(),
        s.structuredResultJson(),
        s.suggestedSeverity(),
        s.suggestedCategory(),
        s.failureReason(),
        s.createdAt(),
        s.reviewStatus().name(),
        s.reviewedBy(),
        s.reviewedAt(),
        s.acceptedFieldsJson(),
        s.reviewFeedback());
  }
}
