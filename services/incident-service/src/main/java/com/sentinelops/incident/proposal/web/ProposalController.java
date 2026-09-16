package com.sentinelops.incident.proposal.web;

import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.proposal.AgentApprovalService;
import com.sentinelops.incident.proposal.AgentProposalRepository;
import com.sentinelops.incident.proposal.AgentProposalService;
import com.sentinelops.incident.proposal.ProposalActionType;
import com.sentinelops.incident.proposal.ProposalNotFoundException;
import com.sentinelops.incident.proposal.ProposeActionCommand;
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
 * Agent-proposal REST API backing the operator console's "Agent Proposals" view (section 9).
 * MCP-created and console-created proposals go through the exact same {@link
 * AgentProposalService}/{@link AgentApprovalService} — there is no separate path.
 */
@RestController
public class ProposalController {

  private static final String READ_ROLES = "hasAnyRole('VIEWER','RESPONDER','ADMIN')";
  private static final String WRITE_ROLES = "hasAnyRole('RESPONDER','ADMIN')";

  private final AgentProposalService proposalService;
  private final AgentApprovalService approvalService;
  private final AgentProposalRepository repository;
  private final AuthenticatedActor authenticatedActor;

  public ProposalController(
      AgentProposalService proposalService,
      AgentApprovalService approvalService,
      AgentProposalRepository repository,
      AuthenticatedActor authenticatedActor) {
    this.proposalService = proposalService;
    this.approvalService = approvalService;
    this.repository = repository;
    this.authenticatedActor = authenticatedActor;
  }

  @Operation(summary = "List the most recent agent proposals across all incidents")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/proposals")
  public List<ProposalResponse> listRecent() {
    return repository.findAllOrderedByCreatedAtDesc(100).stream()
        .map(ProposalResponse::from)
        .toList();
  }

  @Operation(summary = "Get a single agent proposal")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/proposals/{id}")
  public ProposalResponse get(@PathVariable UUID id) {
    return repository
        .findById(id)
        .map(ProposalResponse::from)
        .orElseThrow(() -> new ProposalNotFoundException(id));
  }

  @Operation(summary = "List agent proposals for one incident")
  @PreAuthorize(READ_ROLES)
  @GetMapping("/api/v1/incidents/{incidentId}/proposals")
  public List<ProposalResponse> listForIncident(@PathVariable UUID incidentId) {
    return repository.findByIncidentId(incidentId).stream().map(ProposalResponse::from).toList();
  }

  @Operation(
      summary = "Propose an action on an incident",
      description =
          "Validated and durably recorded, but never executed here — see the approve endpoint. Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/api/v1/incidents/{incidentId}/proposals")
  public ResponseEntity<ProposalResponse> propose(
      @PathVariable UUID incidentId,
      @Valid @RequestBody CreateProposalRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    var proposal =
        proposalService.propose(
            new ProposeActionCommand(
                incidentId,
                ProposalActionType.valueOf(request.actionType()),
                request.parameters() == null ? java.util.Map.of() : request.parameters(),
                request.reason(),
                request.evidenceReferences(),
                request.expectedVersion(),
                authenticatedActor.currentActorId(),
                ActorType.LOCAL_USER.name(),
                correlationId,
                request.idempotencyKey()));
    return ResponseEntity.status(HttpStatus.CREATED).body(ProposalResponse.from(proposal));
  }

  @Operation(
      summary = "Approve and execute an agent proposal",
      description =
          "The approving actor may never be the same actor who created the proposal. Requires the RESPONDER or ADMIN role; replaying a dead-lettered event additionally requires ADMIN.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/api/v1/proposals/{id}/approve")
  public ProposalResponse approve(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) ReviewRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    boolean isAdmin = authenticatedActor.currentRoles().contains("ADMIN");
    String reviewNote = request == null ? null : request.reviewNote();
    var proposal =
        approvalService.approveAndExecute(
            id, authenticatedActor.currentActorId(), isAdmin, reviewNote, correlationId);
    return ProposalResponse.from(proposal);
  }

  @Operation(
      summary = "Reject an agent proposal",
      description = "Requires the RESPONDER or ADMIN role.")
  @PreAuthorize(WRITE_ROLES)
  @PostMapping("/api/v1/proposals/{id}/reject")
  public ProposalResponse reject(
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) ReviewRequest request,
      HttpServletRequest servletRequest) {
    String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
    String reviewNote = request == null ? null : request.reviewNote();
    var proposal =
        approvalService.reject(id, authenticatedActor.currentActorId(), reviewNote, correlationId);
    return ProposalResponse.from(proposal);
  }
}
