package com.sentinelops.incident.remediation.web;

import com.sentinelops.incident.remediation.execution.RemediationExecutionRepository;
import com.sentinelops.incident.remediation.execution.RemediationExecutionService;
import com.sentinelops.incident.remediation.execution.RemediationNotFoundException;
import com.sentinelops.incident.remediation.execution.RemediationProposeRequest;
import com.sentinelops.incident.remediation.execution.RemediationStepRepository;
import com.sentinelops.incident.remediation.runbook.RemediationRunbookRepository;
import com.sentinelops.incident.remediation.runbook.RunbookNotFoundException;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator-console REST API for the remediation engine (section: "Operator console"). Never runs a
 * step itself — every mutating endpoint here delegates to {@link RemediationExecutionService},
 * which is the same code path an MCP-proposed remediation goes through, so the console and an agent
 * proposal are always subject to identical policy/approval/blast-radius checks.
 */
@RestController
public class RemediationController {

  private static final String READ_ROLES = "hasAnyRole('VIEWER','RESPONDER','ADMIN')";
  private static final String WRITE_ROLES = "hasAnyRole('RESPONDER','ADMIN')";
  private static final String ADMIN_ROLE = "hasRole('ADMIN')";

  private final RemediationExecutionService executionService;
  private final RemediationExecutionRepository executionRepository;
  private final RemediationStepRepository stepRepository;
  private final RemediationRunbookRepository runbookRepository;
  private final AuthenticatedActor authenticatedActor;

  public RemediationController(
      RemediationExecutionService executionService,
      RemediationExecutionRepository executionRepository,
      RemediationStepRepository stepRepository,
      RemediationRunbookRepository runbookRepository,
      AuthenticatedActor authenticatedActor) {
    this.executionService = executionService;
    this.executionRepository = executionRepository;
    this.stepRepository = stepRepository;
    this.runbookRepository = runbookRepository;
    this.authenticatedActor = authenticatedActor;
  }

  @Operation(summary = "List the active remediation runbooks available to propose")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/runbooks")
  public List<RunbookResponse> listRunbooks() {
    return runbookRepository.findAllActive().stream().map(RunbookResponse::from).toList();
  }

  @Operation(summary = "Get one active runbook's definition, for preview before proposing")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/runbooks/{slug}")
  public RunbookResponse getRunbook(@PathVariable String slug) {
    return runbookRepository
        .findActiveBySlug(slug)
        .map(RunbookResponse::from)
        .orElseThrow(() -> new RunbookNotFoundException(slug));
  }

  @Operation(summary = "List the most recent remediation executions across all incidents")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/remediations")
  public List<RemediationExecutionResponse> listRecent() {
    return executionRepository.findAllOrderedByCreatedAtDesc(100).stream()
        .map(RemediationExecutionResponse::from)
        .toList();
  }

  @Operation(summary = "List remediation executions for one incident")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/incidents/{incidentId}/remediations")
  public List<RemediationExecutionResponse> listForIncident(@PathVariable UUID incidentId) {
    return executionRepository.findByIncidentId(incidentId).stream()
        .map(RemediationExecutionResponse::from)
        .toList();
  }

  @Operation(summary = "Get a single remediation execution")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/remediations/{id}")
  public RemediationExecutionResponse get(@PathVariable UUID id) {
    return executionRepository
        .findById(id)
        .map(RemediationExecutionResponse::from)
        .orElseThrow(() -> new RemediationNotFoundException(id));
  }

  @Operation(summary = "Get step-level detail (including dry-run plan output) for one execution")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/remediations/{id}/steps")
  public List<RemediationStepResponse> steps(@PathVariable UUID id) {
    return stepRepository.findByExecutionId(id).stream()
        .map(RemediationStepResponse::from)
        .toList();
  }

  @Operation(
      summary = "Propose (or dry-run) a remediation execution from a runbook",
      description =
          "Always policy-evaluated before anything runs; a LOW-risk, unrestricted-environment "
              + "action may auto-approve and schedule immediately, everything else waits for "
              + "human approval. Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/api/v1/remediations")
  public ResponseEntity<RemediationExecutionResponse> propose(
      @Valid @RequestBody CreateRemediationRequest request, HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    var execution =
        executionService.propose(
            new RemediationProposeRequest(
                request.runbookSlug(),
                request.incidentId() == null ? null : UUID.fromString(request.incidentId()),
                request.proposalId() == null ? null : UUID.fromString(request.proposalId()),
                request.dryRun(),
                authenticatedActor.currentActorId(),
                authenticatedActor.currentRoles(),
                correlationId,
                request.idempotencyKey()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(RemediationExecutionResponse.from(execution));
  }

  @Operation(
      summary = "Approve a remediation execution awaiting approval",
      description =
          "The approving actor may never be the same actor who requested it. HIGH-risk runbooks "
              + "require two distinct approvers before the execution is scheduled.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/api/v1/remediations/{id}/approve")
  public RemediationExecutionResponse approve(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) ReviewRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    String note = request == null ? null : request.note();
    var execution =
        executionService.approve(id, authenticatedActor.currentActorId(), note, correlationId);
    return RemediationExecutionResponse.from(execution);
  }

  @Operation(summary = "Reject a remediation execution awaiting approval")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/api/v1/remediations/{id}/reject")
  public RemediationExecutionResponse reject(
      @PathVariable UUID id, HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    var execution = executionService.reject(id, authenticatedActor.currentActorId(), correlationId);
    return RemediationExecutionResponse.from(execution);
  }

  @Operation(
      summary = "Cancel a remediation execution",
      description =
          "Cooperative while RUNNING (checked between steps); immediate for PROPOSED/APPROVED/SCHEDULED.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/api/v1/remediations/{id}/cancel")
  public RemediationExecutionResponse cancel(
      @PathVariable UUID id, HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    var execution = executionService.cancel(id, authenticatedActor.currentActorId(), correlationId);
    return RemediationExecutionResponse.from(execution);
  }

  @Operation(
      summary = "Emergency-stop a running or scheduled remediation execution",
      description = "Administrator only.")
  @PreAuthorize(ADMIN_ROLE)
  @PostMapping("/api/v1/remediations/{id}/emergency-stop")
  public RemediationExecutionResponse emergencyStop(
      @PathVariable UUID id, HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    var execution =
        executionService.emergencyStop(
            id,
            authenticatedActor.currentRoles().contains("ADMIN"),
            authenticatedActor.currentActorId(),
            correlationId);
    return RemediationExecutionResponse.from(execution);
  }
}
