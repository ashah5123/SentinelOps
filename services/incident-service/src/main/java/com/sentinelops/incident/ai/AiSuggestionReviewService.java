package com.sentinelops.incident.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.domain.IncidentSeverity;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Human review of a persisted {@link AiSuggestion}. Accepting a field never writes to {@code
 * incidents.incidents} directly — it calls the same authorized, audited command path any other
 * write would use ({@link IncidentCommandService}). Only two fields are currently acceptable:
 * "severity" (an existing incident column, mutated via {@link
 * IncidentCommandService#changeSeverity}) and "category" (no such incident column exists, so
 * accepting it only updates this suggestion's own review record — see the migration comment on
 * {@code incidents.ai_suggestions}).
 */
@Service
public class AiSuggestionReviewService {

  private static final Set<String> ACCEPTABLE_FIELDS = Set.of("severity", "category");

  private final AiSuggestionRepository suggestionRepository;
  private final IncidentCommandService incidentCommandService;
  private final ObjectMapper objectMapper;
  private final AiMetrics metrics;

  public AiSuggestionReviewService(
      AiSuggestionRepository suggestionRepository,
      IncidentCommandService incidentCommandService,
      ObjectMapper objectMapper,
      AiMetrics metrics) {
    this.suggestionRepository = suggestionRepository;
    this.incidentCommandService = incidentCommandService;
    this.objectMapper = objectMapper;
    this.metrics = metrics;
  }

  public record ReviewCommand(List<String> acceptedFields, String feedback) {}

  public AiSuggestion review(
      UUID suggestionId, ReviewCommand command, String correlationId, String reviewerId) {
    AiSuggestion suggestion =
        suggestionRepository
            .findById(suggestionId)
            .orElseThrow(() -> new AiSuggestionNotFoundException(suggestionId));

    List<String> accepted = command.acceptedFields() == null ? List.of() : command.acceptedFields();
    for (String field : accepted) {
      if (!ACCEPTABLE_FIELDS.contains(field)) {
        throw new IllegalArgumentException(
            "Unknown acceptable field '" + field + "' (expected one of " + ACCEPTABLE_FIELDS + ")");
      }
    }

    if (accepted.contains("severity")) {
      if (suggestion.suggestedSeverity() == null) {
        throw new IllegalStateException("This suggestion has no suggestedSeverity to accept");
      }
      incidentCommandService.changeSeverity(
          suggestion.incidentId(),
          IncidentSeverity.valueOf(suggestion.suggestedSeverity()),
          "Accepted from AI suggestion " + suggestionId,
          correlationId,
          reviewerId);
    }

    AiSuggestion.ReviewStatus reviewStatus;
    if (accepted.isEmpty()) {
      reviewStatus = AiSuggestion.ReviewStatus.REJECTED;
    } else if (accepted.containsAll(ACCEPTABLE_FIELDS)) {
      reviewStatus = AiSuggestion.ReviewStatus.ACCEPTED;
    } else {
      reviewStatus = AiSuggestion.ReviewStatus.PARTIAL;
    }

    suggestionRepository.recordReview(
        suggestionId,
        reviewStatus,
        reviewerId,
        Instant.now(),
        writeJson(accepted),
        command.feedback());

    metrics.reviewRecorded(reviewStatus.name().toLowerCase(java.util.Locale.ROOT));

    return suggestionRepository.findById(suggestionId).orElseThrow();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize accepted fields", e);
    }
  }
}
