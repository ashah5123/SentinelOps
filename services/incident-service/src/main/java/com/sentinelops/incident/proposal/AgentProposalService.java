package com.sentinelops.incident.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stage 1 of the two-stage agent-operations design (section 8): validates and durably records a
 * proposed action. <b>Never executes anything</b> — see {@code AgentApprovalService} for the only
 * path that can turn a proposal into a real domain mutation, and only after a human approval.
 */
@Service
public class AgentProposalService {

  static final Duration DEFAULT_PROPOSAL_TTL = Duration.ofHours(24);

  private final IncidentQueryService incidentQueryService;
  private final AgentProposalRepository repository;
  private final AuditRecorder auditRecorder;
  private final ObjectMapper objectMapper;
  private final ProposalRateLimiter rateLimiter;

  public AgentProposalService(
      IncidentQueryService incidentQueryService,
      AgentProposalRepository repository,
      AuditRecorder auditRecorder,
      ObjectMapper objectMapper,
      ProposalRateLimiter rateLimiter) {
    this.incidentQueryService = incidentQueryService;
    this.repository = repository;
    this.auditRecorder = auditRecorder;
    this.objectMapper = objectMapper;
    this.rateLimiter = rateLimiter;
  }

  @Transactional
  public AgentProposalRow propose(ProposeActionCommand command) {
    var existing = repository.findByIdempotencyKey(command.idempotencyKey());
    if (existing.isPresent()) {
      return existing.get();
    }
    if (!rateLimiter.tryAcquire(command.requestedBy())) {
      throw new ProposalConflictException(
          "RATE_LIMITED", "Too many proposals created recently — please retry after a short delay");
    }

    Incident incident = incidentQueryService.getOrThrow(command.incidentId());
    validateParameters(command);

    String contentHash =
        ProposalContentHash.compute(
            objectMapper,
            command.incidentId(),
            command.actionType(),
            command.parameters(),
            command.expectedVersion());
    RiskClassification risk = classifyRisk(command);
    Instant now = Instant.now();

    AgentProposalRow row =
        new AgentProposalRow(
            UUID.randomUUID(),
            incident.getId(),
            command.actionType(),
            command.parameters(),
            command.reason(),
            command.evidenceReferences() == null ? List.of() : command.evidenceReferences(),
            command.expectedVersion(),
            contentHash,
            risk,
            command.requestedBy(),
            command.requestedActorType(),
            command.correlationId(),
            command.idempotencyKey(),
            now,
            now.plus(DEFAULT_PROPOSAL_TTL),
            ProposalStatus.PENDING,
            null,
            null,
            null,
            null,
            false,
            null,
            null,
            null,
            null,
            null,
            null);

    UUID id =
        repository
            .tryInsert(row)
            .orElseGet(
                () -> repository.findByIdempotencyKey(command.idempotencyKey()).orElseThrow().id());

    auditRecorder.record(
        incident.getId(),
        "AGENT_PROPOSAL_CREATED",
        actorTypeOf(command.requestedActorType()),
        command.requestedBy(),
        command.correlationId(),
        Map.of(
            "proposalId",
            id.toString(),
            "actionType",
            command.actionType().name(),
            "risk",
            risk.name()));

    return repository.findById(id).orElseThrow();
  }

  private void validateParameters(ProposeActionCommand command) {
    Map<String, Object> p = command.parameters() == null ? Map.of() : command.parameters();
    if (command.reason() == null || command.reason().isBlank()) {
      throw new ProposalValidationException("reason is required");
    }
    switch (command.actionType()) {
      case ASSIGN -> requireNonBlankString(p, "assigneeId");
      case CHANGE_SEVERITY -> {
        String severity = requireNonBlankString(p, "severity");
        try {
          IncidentSeverity.valueOf(severity);
        } catch (IllegalArgumentException e) {
          throw new ProposalValidationException("severity must be one of SEV1..SEV4");
        }
      }
      case ADD_NOTE -> {
        String note = requireNonBlankString(p, "note");
        if (note.length() > 1000) {
          throw new ProposalValidationException("note exceeds the maximum length of 1000");
        }
      }
      case REPLAY_DEAD_LETTER -> {
        String topic = requireNonBlankString(p, "topic");
        if (!com.sentinelops.incident.application.DeadLetterReplayService.isEligible(topic)) {
          throw new ProposalValidationException(
              "topic is not an eligible dead-letter topic: "
                  + com.sentinelops.incident.application.DeadLetterReplayService.eligibleTopics());
        }
        Object maxRecords = p.get("maxRecords");
        if (maxRecords != null) {
          int value = ((Number) maxRecords).intValue();
          if (value < 1 || value > 100) {
            throw new ProposalValidationException("maxRecords must be between 1 and 100");
          }
        }
      }
      case ACKNOWLEDGE, ESCALATE, RESOLVE -> {
        // No action-specific parameters required.
      }
    }
  }

  private String requireNonBlankString(Map<String, Object> params, String key) {
    Object value = params.get(key);
    if (!(value instanceof String s) || s.isBlank()) {
      throw new ProposalValidationException(key + " is required and must be a non-blank string");
    }
    return s;
  }

  private static final Set<ProposalActionType> HIGH_RISK =
      Set.of(ProposalActionType.RESOLVE, ProposalActionType.REPLAY_DEAD_LETTER);
  private static final Set<ProposalActionType> LOW_RISK =
      Set.of(ProposalActionType.ACKNOWLEDGE, ProposalActionType.ADD_NOTE);

  private RiskClassification classifyRisk(ProposeActionCommand command) {
    if (command.actionType() == ProposalActionType.CHANGE_SEVERITY) {
      Object severity = command.parameters().get("severity");
      if ("SEV1".equals(severity)) {
        return RiskClassification.HIGH;
      }
      return RiskClassification.MEDIUM;
    }
    if (HIGH_RISK.contains(command.actionType())) {
      return RiskClassification.HIGH;
    }
    if (LOW_RISK.contains(command.actionType())) {
      return RiskClassification.LOW;
    }
    return RiskClassification.MEDIUM;
  }

  private ActorType actorTypeOf(String requestedActorType) {
    try {
      return ActorType.valueOf(requestedActorType);
    } catch (IllegalArgumentException e) {
      return ActorType.LOCAL_USER;
    }
  }
}
