package com.sentinelops.incident.web.controller;

import com.sentinelops.incident.application.AuditFilter;
import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.DeadLetterReplayService;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.security.AuthenticatedActor;
import com.sentinelops.incident.web.dto.AuditEventResponse;
import com.sentinelops.incident.web.dto.DeadLetterReplayResponse;
import com.sentinelops.incident.web.dto.PageResponse;
import com.sentinelops.incident.web.filter.CorrelationIdFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only operational endpoints: the global audit trail and manual dead-letter replay. Every
 * endpoint here requires the {@code ADMIN} role (enforced both here and, redundantly, in {@code
 * SecurityConfig} as defense in depth) — see the role-permission matrix in {@code
 * services/incident-service/README.md}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

  private static final int MAX_REPLAY_RECORDS = 100;
  private static final int DEFAULT_REPLAY_RECORDS = 20;

  private final IncidentQueryService queryService;
  private final DeadLetterReplayService deadLetterReplayService;
  private final AuditRecorder auditRecorder;
  private final AuthenticatedActor authenticatedActor;

  public AdminController(
      IncidentQueryService queryService,
      DeadLetterReplayService deadLetterReplayService,
      AuditRecorder auditRecorder,
      AuthenticatedActor authenticatedActor) {
    this.queryService = queryService;
    this.deadLetterReplayService = deadLetterReplayService;
    this.auditRecorder = auditRecorder;
    this.authenticatedActor = authenticatedActor;
  }

  @Operation(
      summary = "Search the append-only operational audit trail",
      description =
          "Application-enforced append-only log: no endpoint exists to edit or delete an audit "
              + "record. Results are bounded and paginated; all filters are optional.")
  @GetMapping("/audit-events")
  public PageResponse<AuditEventResponse> searchAuditEvents(
      @Parameter(description = "Filter by actor subject ID") @RequestParam(required = false)
          String actorId,
      @Parameter(description = "Filter by actor type") @RequestParam(required = false)
          ActorType actorType,
      @Parameter(description = "Filter by action name") @RequestParam(required = false)
          String action,
      @Parameter(description = "Filter by incident ID") @RequestParam(required = false)
          UUID incidentId,
      @Parameter(description = "Only events at or after this instant")
          @RequestParam(required = false)
          Instant occurredFrom,
      @Parameter(description = "Only events at or before this instant")
          @RequestParam(required = false)
          Instant occurredTo,
      @PageableDefault(size = 20, sort = "occurredAt", direction = Sort.Direction.DESC)
          Pageable pageable) {
    Pageable deterministicPageable =
        org.springframework.data.domain.PageRequest.of(
            pageable.getPageNumber(),
            Math.min(pageable.getPageSize(), 100),
            pageable.getSort().and(Sort.by("id")));
    var page =
        queryService.searchAuditEvents(
            new AuditFilter(actorId, actorType, action, incidentId, occurredFrom, occurredTo),
            deterministicPageable);
    return PageResponse.from(page, AuditEventResponse::from);
  }

  @Operation(
      summary = "Replay eligible dead-lettered events back to their original topic",
      description =
          "Only the dead-letter topics this service itself consumes from are eligible; see "
              + "docs/development/reliability.md. Replayed events keep their original event ID, so "
              + "the target topic's own idempotent-consumption check still applies.")
  @PostMapping("/dead-letter-topics/{topic}/replay")
  @Transactional
  public DeadLetterReplayResponse replayDeadLetterTopic(
      @PathVariable String topic,
      @Parameter(description = "Maximum records to replay in this call (bounded, default 20)")
          @RequestParam(required = false)
          Integer maxRecords,
      HttpServletRequest servletRequest) {
    int bounded =
        Math.max(
            1,
            Math.min(maxRecords == null ? DEFAULT_REPLAY_RECORDS : maxRecords, MAX_REPLAY_RECORDS));
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);

    DeadLetterReplayService.ReplayResult result = deadLetterReplayService.replay(topic, bounded);

    auditRecorder.record(
        null,
        "DEAD_LETTER_REPLAYED",
        authenticatedActor.actorType(),
        authenticatedActor.currentActorId(),
        correlationId,
        Map.of(
            "dlqTopic", result.dlqTopic(),
            "targetTopic", result.targetTopic(),
            "replayedCount", result.replayed(),
            "failedCount", result.failed()));

    return DeadLetterReplayResponse.from(result);
  }
}
