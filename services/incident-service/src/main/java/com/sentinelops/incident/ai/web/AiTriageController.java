package com.sentinelops.incident.ai.web;

import com.sentinelops.incident.ai.AiSuggestion;
import com.sentinelops.incident.ai.AiSuggestionReviewService;
import com.sentinelops.incident.ai.AiSuggestionReviewService.ReviewCommand;
import com.sentinelops.incident.ai.AiTriageService;
import com.sentinelops.incident.security.AuthenticatedActor;
import com.sentinelops.incident.web.filter.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI-assisted triage endpoints. Never mutates an incident directly — see {@link AiTriageService}
 * and {@link AiSuggestionReviewService}. Same role model as {@code IncidentController}: generating
 * and reviewing a suggestion requires RESPONDER or ADMIN; reading suggestions only requires any
 * authenticated role.
 */
@RestController
@RequestMapping("/api/v1/incidents/{incidentId}/ai-suggestions")
public class AiTriageController {

  private static final String READ_ROLES = "hasAnyRole('VIEWER','RESPONDER','ADMIN')";
  private static final String WRITE_ROLES = "hasAnyRole('RESPONDER','ADMIN')";

  private final AiTriageService triageService;
  private final AiSuggestionReviewService reviewService;
  private final com.sentinelops.incident.ai.AiSuggestionRepository suggestionRepository;
  private final AuthenticatedActor authenticatedActor;

  public AiTriageController(
      AiTriageService triageService,
      AiSuggestionReviewService reviewService,
      com.sentinelops.incident.ai.AiSuggestionRepository suggestionRepository,
      AuthenticatedActor authenticatedActor) {
    this.triageService = triageService;
    this.reviewService = reviewService;
    this.suggestionRepository = suggestionRepository;
    this.authenticatedActor = authenticatedActor;
  }

  @Operation(
      summary = "Request an AI-generated triage suggestion for an incident",
      description =
          "Never mutates the incident. Rate-limited per incident (see "
              + "sentinelops.ai.rate-limit.cooldown). Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping
  public ResponseEntity<AiSuggestionResponse> generate(
      @PathVariable UUID incidentId, HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    AiSuggestion suggestion =
        triageService.generate(incidentId, correlationId, authenticatedActor.currentActorId());
    return ResponseEntity.status(HttpStatus.CREATED).body(AiSuggestionResponse.from(suggestion));
  }

  @Operation(summary = "List AI triage suggestions for an incident, most recent first")
  @PreAuthorize(READ_ROLES)
  @GetMapping
  public List<AiSuggestionResponse> list(@PathVariable UUID incidentId) {
    return suggestionRepository.findByIncidentId(incidentId).stream()
        .map(AiSuggestionResponse::from)
        .toList();
  }

  @Operation(summary = "Get a single AI triage suggestion")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/{suggestionId}")
  public AiSuggestionResponse get(@PathVariable UUID incidentId, @PathVariable UUID suggestionId) {
    return AiSuggestionResponse.from(
        suggestionRepository
            .findById(suggestionId)
            .orElseThrow(
                () -> new com.sentinelops.incident.ai.AiSuggestionNotFoundException(suggestionId)));
  }

  @Operation(
      summary = "Record a human review decision for an AI triage suggestion",
      description =
          "Accepting \"severity\" applies it through the normal incident severity-change command "
              + "path; accepting \"category\" only updates this suggestion's own review record, "
              + "since incidents have no category field. Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/{suggestionId}/review")
  public AiSuggestionResponse review(
      @PathVariable UUID incidentId,
      @PathVariable UUID suggestionId,
      @Valid @RequestBody AiReviewRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    AiSuggestion reviewed =
        reviewService.review(
            suggestionId,
            new ReviewCommand(request.acceptedFields(), request.feedback()),
            correlationId,
            authenticatedActor.currentActorId());
    return AiSuggestionResponse.from(reviewed);
  }
}
