package com.sentinelops.incident.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.alerts.notification.NotificationRenderer;
import com.sentinelops.incident.alerts.notification.NotificationRepository;
import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.DeadLetterReplayService;
import com.sentinelops.incident.application.IncidentCommandService;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 2 of the two-stage design (section 8). {@link #approveAndExecute} is the only path that can
 * turn a proposal into a real domain mutation — it always runs through the existing {@code
 * IncidentCommandService} (never a direct repository write), verifies the incident's current
 * version still matches what the proposal was reviewed against, re-verifies the proposal's content
 * hash (so altered parameters are rejected even in principle), and consumes the approval atomically
 * so a replay of this exact call can never execute twice.
 */
@Service
public class AgentApprovalService {

  private static final Logger log = LoggerFactory.getLogger(AgentApprovalService.class);
  private static final Duration APPROVAL_EXECUTION_WINDOW = Duration.ofMinutes(15);

  private final AgentProposalRepository repository;
  private final IncidentQueryService incidentQueryService;
  private final IncidentCommandService incidentCommandService;
  private final DeadLetterReplayService deadLetterReplayService;
  private final NotificationRepository notificationRepository;
  private final NotificationRenderer notificationRenderer;
  private final AuditRecorder auditRecorder;
  private final ObjectMapper objectMapper;

  public AgentApprovalService(
      AgentProposalRepository repository,
      IncidentQueryService incidentQueryService,
      IncidentCommandService incidentCommandService,
      DeadLetterReplayService deadLetterReplayService,
      NotificationRepository notificationRepository,
      NotificationRenderer notificationRenderer,
      AuditRecorder auditRecorder,
      ObjectMapper objectMapper) {
    this.repository = repository;
    this.incidentQueryService = incidentQueryService;
    this.incidentCommandService = incidentCommandService;
    this.deadLetterReplayService = deadLetterReplayService;
    this.notificationRepository = notificationRepository;
    this.notificationRenderer = notificationRenderer;
    this.auditRecorder = auditRecorder;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public AgentProposalRow reject(
      UUID proposalId, String rejectedBy, String rejectionNote, String correlationId) {
    AgentProposalRow proposal =
        repository
            .findByIdForUpdate(proposalId)
            .orElseThrow(() -> new ProposalNotFoundException(proposalId));
    if (proposal.status() != ProposalStatus.PENDING) {
      throw new ProposalConflictException(
          "NOT_PENDING", "Proposal is not pending: " + proposal.status());
    }
    boolean ok = repository.reject(proposalId, rejectedBy, Instant.now(), rejectionNote);
    if (!ok) {
      throw new ProposalConflictException(
          "CONCURRENT_MODIFICATION", "Proposal was modified concurrently");
    }
    auditRecorder.record(
        proposal.incidentId(),
        "AGENT_PROPOSAL_REJECTED",
        ActorType.LOCAL_USER,
        rejectedBy,
        correlationId,
        Map.of("proposalId", proposalId.toString()));
    return repository.findById(proposalId).orElseThrow();
  }

  /**
   * Approves and immediately executes the proposal in one server-side operation — a single console
   * action, but every safety property (approval consumption, content-hash re-check, stale-state
   * detection) still applies exactly as if the two were separate calls, which is what makes a
   * replay of this same call safely idempotent-and-blocked rather than a double-execution.
   */
  @Transactional
  public AgentProposalRow approveAndExecute(
      UUID proposalId,
      String approverActorId,
      boolean approverIsAdmin,
      String reviewNote,
      String correlationId) {
    AgentProposalRow proposal =
        repository
            .findByIdForUpdate(proposalId)
            .orElseThrow(() -> new ProposalNotFoundException(proposalId));

    if (proposal.requestedBy().equals(approverActorId)) {
      throw new ProposalConflictException(
          "SELF_APPROVAL", "An actor cannot approve their own proposal");
    }
    if (proposal.actionType() == ProposalActionType.REPLAY_DEAD_LETTER && !approverIsAdmin) {
      throw new ProposalConflictException(
          "INSUFFICIENT_PERMISSION", "Only an administrator may approve a dead-letter replay");
    }
    if (proposal.status() != ProposalStatus.PENDING) {
      throw new ProposalConflictException(
          "NOT_PENDING", "Proposal is not pending: " + proposal.status());
    }
    Instant now = Instant.now();
    if (now.isAfter(proposal.expiresAt())) {
      repository.markExpired(proposalId);
      auditRecorder.record(
          proposal.incidentId(),
          "AGENT_PROPOSAL_EXPIRED",
          ActorType.SYSTEM,
          "system",
          correlationId,
          Map.of("proposalId", proposalId.toString()));
      throw new ProposalConflictException("EXPIRED", "Proposal expired at " + proposal.expiresAt());
    }

    Instant approvalExpiresAt = now.plus(APPROVAL_EXECUTION_WINDOW);
    boolean approved =
        repository.approve(proposalId, approverActorId, now, reviewNote, approvalExpiresAt);
    if (!approved) {
      throw new ProposalConflictException(
          "CONCURRENT_MODIFICATION", "Proposal was modified concurrently");
    }
    auditRecorder.record(
        proposal.incidentId(),
        "AGENT_PROPOSAL_APPROVED",
        ActorType.LOCAL_USER,
        approverActorId,
        correlationId,
        Map.of("proposalId", proposalId.toString()));

    return execute(proposalId, approverActorId, correlationId);
  }

  private AgentProposalRow execute(UUID proposalId, String executorActorId, String correlationId) {
    boolean consumed = repository.consumeApproval(proposalId);
    if (!consumed) {
      throw new ProposalConflictException(
          "APPROVAL_ALREADY_CONSUMED",
          "This approval has already been used to execute the proposal");
    }

    AgentProposalRow proposal =
        repository
            .findById(proposalId)
            .orElseThrow(() -> new ProposalNotFoundException(proposalId));

    String recomputedHash =
        ProposalContentHash.compute(
            objectMapper,
            proposal.incidentId(),
            proposal.actionType(),
            proposal.parameters(),
            proposal.expectedVersion());
    if (!recomputedHash.equals(proposal.contentHash())) {
      String error = "Proposal content hash mismatch — refusing to execute altered parameters";
      repository.markExecutionFailed(proposalId, error);
      auditRecorder.record(
          proposal.incidentId(),
          "AGENT_PROPOSAL_EXECUTION_FAILED",
          ActorType.LOCAL_USER,
          executorActorId,
          correlationId,
          Map.of("proposalId", proposalId.toString(), "reason", "content_hash_mismatch"));
      throw new ProposalConflictException("CONTENT_HASH_MISMATCH", error);
    }

    Incident incident = incidentQueryService.getOrThrow(proposal.incidentId());
    if (incident.getVersion() != proposal.expectedVersion()) {
      String error =
          "Incident state changed since the proposal was created (expected version "
              + proposal.expectedVersion()
              + ", now "
              + incident.getVersion()
              + ")";
      repository.markExecutionFailed(proposalId, error);
      auditRecorder.record(
          proposal.incidentId(),
          "AGENT_PROPOSAL_EXECUTION_FAILED",
          ActorType.LOCAL_USER,
          executorActorId,
          correlationId,
          Map.of("proposalId", proposalId.toString(), "reason", "stale_state"));
      throw new ProposalConflictException("STALE_STATE", error);
    }

    try {
      String resultSummary = applyAction(proposal, incident, executorActorId, correlationId);
      repository.markExecuted(proposalId, Instant.now(), resultSummary);
      auditRecorder.record(
          proposal.incidentId(),
          "AGENT_PROPOSAL_EXECUTED",
          ActorType.LOCAL_USER,
          executorActorId,
          correlationId,
          Map.of("proposalId", proposalId.toString(), "actionType", proposal.actionType().name()));
      return repository.findById(proposalId).orElseThrow();
    } catch (RuntimeException e) {
      String sanitized =
          e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
      repository.markExecutionFailed(proposalId, sanitized);
      auditRecorder.record(
          proposal.incidentId(),
          "AGENT_PROPOSAL_EXECUTION_FAILED",
          ActorType.LOCAL_USER,
          executorActorId,
          correlationId,
          Map.of("proposalId", proposalId.toString(), "reason", "domain_error"));
      log.warn("Agent proposal {} execution failed: {}", proposalId, sanitized);
      throw e;
    }
  }

  private String applyAction(
      AgentProposalRow proposal, Incident incident, String executorActorId, String correlationId) {
    Map<String, Object> p = proposal.parameters();
    return switch (proposal.actionType()) {
      case ACKNOWLEDGE -> {
        incidentCommandService.transition(
            incident.getId(),
            IncidentStatus.INVESTIGATING,
            proposal.reason(),
            correlationId,
            executorActorId);
        yield "Transitioned to INVESTIGATING";
      }
      case ASSIGN -> {
        incidentCommandService.assign(
            incident.getId(), (String) p.get("assigneeId"), correlationId, executorActorId);
        yield "Assigned to " + p.get("assigneeId");
      }
      case CHANGE_SEVERITY -> {
        incidentCommandService.changeSeverity(
            incident.getId(),
            IncidentSeverity.valueOf((String) p.get("severity")),
            proposal.reason(),
            correlationId,
            executorActorId);
        yield "Severity changed to " + p.get("severity");
      }
      case ADD_NOTE -> {
        incidentCommandService.addEvidence(
            incident.getId(),
            "RESPONDER_NOTE",
            (String) p.get("note"),
            "agent-proposal:" + proposal.id(),
            correlationId,
            executorActorId);
        yield "Note added";
      }
      case RESOLVE -> {
        incidentCommandService.transition(
            incident.getId(),
            IncidentStatus.RESOLVED,
            proposal.reason(),
            correlationId,
            executorActorId);
        yield "Transitioned to RESOLVED";
      }
      case ESCALATE -> {
        var payload =
            notificationRenderer.render(incident, null, "manual-agent-escalation", 1, "on-call");
        notificationRepository.enqueue(
            UUID.randomUUID(),
            incident.getId(),
            "EMAIL",
            "manual-agent-escalation",
            1,
            "proposal:" + proposal.id(),
            payload,
            Instant.now());
        yield "Manual escalation notification enqueued";
      }
      case REPLAY_DEAD_LETTER -> {
        String topic = (String) p.get("topic");
        int maxRecords =
            p.get("maxRecords") == null ? 20 : ((Number) p.get("maxRecords")).intValue();
        var result = deadLetterReplayService.replay(topic, maxRecords);
        yield "Replayed "
            + result.replayed()
            + " record(s), "
            + result.failed()
            + " failure(s) from "
            + topic;
      }
    };
  }
}
