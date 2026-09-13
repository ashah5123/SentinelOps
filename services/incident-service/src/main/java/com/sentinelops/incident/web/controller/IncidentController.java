package com.sentinelops.incident.web.controller;

import com.sentinelops.incident.application.CreateIncidentCommand;
import com.sentinelops.incident.application.IdempotencyGuard;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.application.IncidentFilter;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentEvidence;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import com.sentinelops.incident.web.dto.AuditEventResponse;
import com.sentinelops.incident.web.dto.CreateIncidentRequest;
import com.sentinelops.incident.web.dto.EvidenceRequest;
import com.sentinelops.incident.web.dto.EvidenceResponse;
import com.sentinelops.incident.web.dto.IncidentResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Incident-management REST API.
 *
 * <p><b>Local-development security boundary:</b> this phase implements no authentication or
 * authorization. Every endpoint here is reachable by anyone who can reach the port it is bound to.
 * It is bound to {@code 127.0.0.1} only in the Compose environment and must never be exposed on a
 * public or shared network. See {@code services/incident-service/README.md}.
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

  private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

  private final IncidentCommandService commandService;
  private final IncidentQueryService queryService;
  private final IdempotencyGuard idempotencyGuard;

  public IncidentController(
      IncidentCommandService commandService,
      IncidentQueryService queryService,
      IdempotencyGuard idempotencyGuard) {
    this.commandService = commandService;
    this.queryService = queryService;
    this.idempotencyGuard = idempotencyGuard;
  }

  @Operation(
      summary = "Create an incident",
      description =
          "Requires an Idempotency-Key header. Repeating the same key with an identical "
              + "payload returns the original response; reusing it with a different payload "
              + "is rejected with 409.")
  @PostMapping
  public ResponseEntity<IncidentResponse> createIncident(
      @RequestHeader(name = IDEMPOTENCY_KEY_HEADER, required = false) String idempotencyKey,
      @Valid @RequestBody CreateIncidentRequest request,
      HttpServletRequest servletRequest) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new MissingIdempotencyKeyException();
    }
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);

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
                          ActorType.LOCAL_USER,
                          "local-operator"));
              return IncidentResponse.from(incident);
            },
            IncidentResponse.class);

    return ResponseEntity.status(outcome.status())
        .location(URI.create("/api/v1/incidents/" + outcome.body().id()))
        .body(outcome.body());
  }

  @Operation(summary = "List incidents with optional filtering, sorting, and pagination")
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
            new IncidentFilter(status, severity, affectedService, detectedFrom, detectedTo),
            deterministicPageable);
    return PageResponse.from(page, IncidentResponse::from);
  }

  @Operation(summary = "Get a single incident by ID")
  @GetMapping("/{id}")
  public IncidentResponse getIncident(@PathVariable UUID id) {
    return IncidentResponse.from(queryService.getOrThrow(id));
  }

  @Operation(
      summary = "Transition an incident to a new status",
      description =
          "Only transitions allowed by the incident lifecycle are accepted; others return 409.")
  @PostMapping("/{id}/transitions")
  public IncidentResponse transition(
      @PathVariable UUID id,
      @Valid @RequestBody TransitionRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    Incident incident =
        commandService.transition(id, request.status(), request.reason(), correlationId);
    return IncidentResponse.from(incident);
  }

  @Operation(summary = "Record a piece of evidence against an incident")
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
            correlationId);
    return ResponseEntity.status(HttpStatus.CREATED).body(EvidenceResponse.from(evidence));
  }

  @Operation(
      summary = "Get the time-ordered timeline of status transitions and evidence for an incident")
  @GetMapping("/{id}/timeline")
  public List<TimelineEntryResponse> getTimeline(@PathVariable UUID id) {
    return queryService.getTimeline(id).stream().map(TimelineEntryResponse::from).toList();
  }

  @Operation(summary = "Get the paginated audit trail for an incident")
  @GetMapping("/{id}/audit-events")
  public PageResponse<AuditEventResponse> getAuditEvents(
      @PathVariable UUID id,
      @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.ASC)
          Pageable pageable) {
    return PageResponse.from(queryService.getAuditEvents(id, pageable), AuditEventResponse::from);
  }
}
