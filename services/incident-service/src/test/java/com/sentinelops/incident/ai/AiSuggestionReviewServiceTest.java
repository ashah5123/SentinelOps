package com.sentinelops.incident.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.domain.IncidentSeverity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AiSuggestionReviewServiceTest {

  private AiSuggestionRepository suggestionRepository;
  private IncidentCommandService incidentCommandService;
  private AiMetrics metrics;
  private AiSuggestionReviewService service;
  private UUID suggestionId;
  private UUID incidentId;

  @BeforeEach
  void setUp() {
    suggestionRepository = mock(AiSuggestionRepository.class);
    incidentCommandService = mock(IncidentCommandService.class);
    metrics = mock(AiMetrics.class);
    service =
        new AiSuggestionReviewService(
            suggestionRepository, incidentCommandService, new ObjectMapper(), metrics);

    suggestionId = UUID.randomUUID();
    incidentId = UUID.randomUUID();
    AiSuggestion suggestion = suggestionFixture();
    when(suggestionRepository.findById(suggestionId))
        .thenReturn(Optional.of(suggestion))
        .thenReturn(Optional.of(suggestion));
  }

  private AiSuggestion suggestionFixture() {
    return new AiSuggestion(
        suggestionId,
        incidentId,
        "responder-1",
        "corr-1",
        "test",
        "test-model",
        "v1",
        "{}",
        AiSuggestion.Status.COMPLETED,
        "{}",
        "SEV1",
        "Performance",
        null,
        Instant.now(),
        AiSuggestion.ReviewStatus.PENDING,
        null,
        null,
        null,
        null);
  }

  @Test
  void acceptingSeverityCallsTheRealAuthorizedCommandPath() {
    service.review(
        suggestionId,
        new AiSuggestionReviewService.ReviewCommand(List.of("severity"), "looks right"),
        "corr-2",
        "reviewer-1");

    verify(incidentCommandService)
        .changeSeverity(
            eq(incidentId), eq(IncidentSeverity.SEV1), any(), eq("corr-2"), eq("reviewer-1"));
    verify(suggestionRepository)
        .recordReview(
            eq(suggestionId),
            eq(AiSuggestion.ReviewStatus.PARTIAL),
            eq("reviewer-1"),
            any(),
            any(),
            eq("looks right"));
  }

  @Test
  void rejectingWithNoAcceptedFieldsNeverTouchesTheIncident() {
    service.review(
        suggestionId,
        new AiSuggestionReviewService.ReviewCommand(List.of(), "not relevant"),
        "corr-2",
        "reviewer-1");

    verify(incidentCommandService, never()).changeSeverity(any(), any(), any(), any(), any());
    verify(suggestionRepository)
        .recordReview(
            eq(suggestionId),
            eq(AiSuggestion.ReviewStatus.REJECTED),
            eq("reviewer-1"),
            any(),
            any(),
            any());
  }

  @Test
  void acceptingBothFieldsRecordsFullAcceptance() {
    service.review(
        suggestionId,
        new AiSuggestionReviewService.ReviewCommand(List.of("severity", "category"), null),
        "corr-2",
        "reviewer-1");

    verify(suggestionRepository)
        .recordReview(
            eq(suggestionId),
            eq(AiSuggestion.ReviewStatus.ACCEPTED),
            eq("reviewer-1"),
            any(),
            any(),
            any());
  }

  @Test
  void anUnknownAcceptedFieldIsRejected() {
    assertThatThrownBy(
            () ->
                service.review(
                    suggestionId,
                    new AiSuggestionReviewService.ReviewCommand(List.of("assignee"), null),
                    "corr-2",
                    "reviewer-1"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(incidentCommandService, never()).changeSeverity(any(), any(), any(), any(), any());
  }

  @Test
  void reviewingAMissingSuggestionThrowsNotFound() {
    UUID missing = UUID.randomUUID();
    when(suggestionRepository.findById(missing)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                service.review(
                    missing,
                    new AiSuggestionReviewService.ReviewCommand(List.of(), null),
                    "corr-2",
                    "reviewer-1"))
        .isInstanceOf(AiSuggestionNotFoundException.class);
  }
}
