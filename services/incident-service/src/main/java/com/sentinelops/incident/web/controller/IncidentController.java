package com.sentinelops.incident.web.controller;

import com.sentinelops.incident.application.CreateIncidentCommand;
import com.sentinelops.incident.application.IdempotencyGuard;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.application.IncidentFilter;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentEvidence;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.security.AuthenticatedActor;
import com.sentinelops.incident.web.dto.AssignmentRequest;
import com.sentinelops.incident.web.dto.AuditEventResponse;
import com.sentinelops.incident.web.dto.CreateIncidentRequest;
import com.sentinelops.incident.web.dto.EvidenceRequest;
import com.sentinelops.incident.web.dto.EvidenceResponse;
import com.sentinelops.incident.web.dto.IncidentResponse;
import com.sentinelops.incident.web.dto.IncidentSummaryResponse;
import com.sentinelops.incident.web.dto.PageResponse;
import com.sentinelops.incident.web.dto.TimelineEntryResponse;
import com.sentinelops.incident.web.dto.TransitionRequest;
import com.sentinelops.incident.web.error.MissingIdempotencyKeyException;
import com.sentinelops.incident.web.filter.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident-management REST API.
 *
 * <p><b>Authentication and authorization (Phase 7):</b> every endpoint requires a valid OAuth2
 * bearer token issued by the configured Keycloak realm; unauthenticated requests receive 401.
 * Endpoints are further restricted by role — see the role-permission matrix in {@code
 * services/incident-service/README.md} — and authenticated-but-unauthorized requests receive 403.
 * The acting user's identity always comes from the validated token's {@code sub} claim (see {@link
 * AuthenticatedActor}), never from a request field, so a caller cannot impersonate another user or
 * grant itself a role it was not issued.
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
  private static final String READ_ROLES = "hasAnyRole('VIEWER','RESPONDER','ADMIN')";
  private static final String WRITE_ROLES = "hasAnyRole('RESPONDER','ADMIN')";
  private static final String ADMIN_ROLE = "hasRole('ADMIN')";

  private final IncidentCommandService commandService;
  private final IncidentQueryService queryService;
  private final IdempotencyGuard idempotencyGuard;
  private final AuthenticatedActor authenticatedActor;

  public IncidentController(
      IncidentCommandService commandService,
      IncidentQueryService queryService,
      IdempotencyGuard idempotencyGuard,
      AuthenticatedActor authenticatedActor) {
    this.commandService = commandService;
    this.queryService = queryService;
    this.idempotencyGuard = idempotencyGuard;
    this.authenticatedActor = authenticatedActor;
  }

  @Operation(
      summary = "Create an incident",
      description =
          "Requires an Idempotency-Key header. Repeating the same key with an identical "
              + "payload returns the original response; reusing it with a different payload "
              + "is rejected with 409. Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping
  public ResponseEntity<IncidentResponse> createIncident(
      @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
      @Valid @RequestBody CreateIncidentRequest request,
      HttpServletRequest servletRequest) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new MissingIdempotencyKeyException();
    }
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    String actorId = authenticatedActor.currentActorId();

    IdempotencyGuard.Outcome<IncidentResponse> outcome =
        idempotencyGuard.execute(
            idempotencyKey,
            request,
            HttpStatus.CREATED.value(),
            () -> {
              Incident incident =
                  commandService.createIncident(
                      new CreateIncidentCommand(
                          request.title(),
                          request.description(),
                          request.severity(),
                          request.source(),
                          request.affectedService(),
                          request.detectedAt(),
                          correlationId,
                          null,
                          authenticatedActor.actorType(),
                          actorId));
              return IncidentResponse.from(incident);
            },
            IncidentResponse.class);

    return ResponseEntity.status(outcome.status())
        .location(URI.create("/api/v1/incidents/" + outcome.body().id()))
        .body(outcome.body());
  }

  @Operation(summary = "List incidents with optional filtering, sorting, and pagination")
  @PreAuthorize(READ_ROLES)
  @GetMapping
  public PageResponse<IncidentResponse> listIncidents(
      @Parameter(description = "Filter by status") @RequestParam(required = false)
          IncidentStatus status,
      @Parameter(description = "Filter by severity") @RequestParam(required = false)
          IncidentSeverity severity,
      @Parameter(description = "Filter by affected service") @RequestParam(required = false)
          String affectedService,
      @Parameter(description = "Only incidents detected at or after this instant")
          @RequestParam(required = false)
          Instant detectedFrom,
      @Parameter(description = "Only incidents detected at or before this instant")
          @RequestParam(required = false)
          Instant detectedTo,
      @Parameter(description = "Filter by assignee actor ID") @RequestParam(required = false)
          String assignee,
      @Parameter(description = "Only unassigned incidents")
          @RequestParam(required = false, defaultValue = "false")
          boolean unassigned,
      @PageableDefault(size = 20, sort = "detectedAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    // Append a stable tiebreaker so identical sort keys always return in the same order.
    Pageable deterministicPageable =
        PageRequest.of(
            pageable.getPageNumber(),
            pageable.getPageSize(),
            pageable.getSort().and(Sort.by("id")));
    var page =
        queryService.list(
            new IncidentFilter(
                status, severity, affectedService, detectedFrom, detectedTo, assignee, unassigned),
            deterministicPageable);
    return PageResponse.from(page, IncidentResponse::from);
  }

  @Operation(
      summary =
          "Bounded dashboard aggregation (counts only) for the same filters as the list endpoint")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/summary")
  public IncidentSummaryResponse getSummary(
      @RequestParam(required = false) IncidentStatus status,
      @RequestParam(required = false) IncidentSeverity severity,
      @RequestParam(required = false) String affectedService,
      @RequestParam(required = false) Instant detectedFrom,
      @RequestParam(required = false) Instant detectedTo,
      @RequestParam(required = false) String assignee,
      @RequestParam(required = false, defaultValue = "false") boolean unassigned) {
    return IncidentSummaryResponse.from(
        queryService.getSummary(
            new IncidentFilter(
                status,
                severity,
                affectedService,
                detectedFrom,
                detectedTo,
                assignee,
                unassigned)));
  }

  @Operation(
      summary = "Assign or unassign an incident",
      description =
          "Send { \"assigneeId\": null } to unassign. Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PutMapping("/{id}/assignee")
  public IncidentResponse assign(
      @PathVariable UUID id,
      @Valid @RequestBody AssignmentRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    Incident incident =
        commandService.assign(
            id, request.assigneeId(), correlationId, authenticatedActor.currentActorId());
    return IncidentResponse.from(incident);
  }

  @Operation(summary = "Get a single incident by ID")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/{id}")
  public IncidentResponse getIncident(@PathVariable UUID id) {
    return IncidentResponse.from(queryService.getOrThrow(id));
  }

  @Operation(
      summary = "Transition an incident to a new status",
      description =
          "Only transitions allowed by the incident lifecycle are accepted; others return 409. "
              + "Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/{id}/transitions")
  public IncidentResponse transition(
      @PathVariable UUID id,
      @Valid @RequestBody TransitionRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    Incident incident =
        commandService.transition(
            id,
            request.status(),
            request.reason(),
            correlationId,
            authenticatedActor.currentActorId());
    return IncidentResponse.from(incident);
  }

  @Operation(
      summary = "Record a piece of evidence against an incident",
      description = "Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/{id}/evidence")
  public ResponseEntity<EvidenceResponse> addEvidence(
      @PathVariable UUID id,
      @Valid @RequestBody EvidenceRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    IncidentEvidence evidence =
        commandService.addEvidence(
            id,
            request.evidenceType(),
            request.description(),
            request.sourceReference(),
            correlationId,
            authenticatedActor.currentActorId());
    return ResponseEntity.status(HttpStatus.CREATED).body(EvidenceResponse.from(evidence));
  }

  @Operation(
      summary = "Get the time-ordered timeline of status transitions and evidence for an incident")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/{id}/timeline")
  public List<TimelineEntryResponse> getTimeline(@PathVariable UUID id) {
    return queryService.getTimeline(id).stream().map(TimelineEntryResponse::from).toList();
  }

  @Operation(
      summary = "Get the paginated audit trail for an incident",
      description = "Operational audit records; requires the ADMIN role.")
  @PreAuthorize(ADMIN_ROLE)
  @GetMapping("/{id}/audit-events")
  public PageResponse<AuditEventResponse> getAuditEvents(
      @PathVariable UUID id,
      @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(queryService.getAuditEvents(id, pageable), AuditEventResponse::from);
  }
}
