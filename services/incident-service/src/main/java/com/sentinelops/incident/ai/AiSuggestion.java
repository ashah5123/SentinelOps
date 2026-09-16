package com.sentinelops.incident.ai;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted AI triage suggestion (table: {@code incidents.ai_suggestions}). Immutable except for
 * the review fields, which only {@link com.sentinelops.incident.ai.web.AiTriageController}'s review
 * endpoint sets, on behalf of an authenticated human reviewer. Never mutates {@code
 * incidents.incidents} — see the migration comment on that table.
 */
public record AiSuggestion(
    UUID id,
    UUID incidentId,
    String requestedBy,
    String correlationId,
    String providerName,
    String modelName,
    String promptTemplateVersion,
    String retrievalConfigJson,
    Status status,
    String structuredResultJson,
    String suggestedSeverity,
    String suggestedCategory,
    String failureReason,
    Instant createdAt,
    ReviewStatus reviewStatus,
    String reviewedBy,
    Instant reviewedAt,
    String acceptedFieldsJson,
    String reviewFeedback) {

  public enum Status {
    COMPLETED,
    FAILED,
    MODEL_UNAVAILABLE
  }

  public enum ReviewStatus {
    PENDING,
    ACCEPTED,
    REJECTED,
    PARTIAL
  }
}
