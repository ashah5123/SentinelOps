package com.sentinelops.incident.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentProposalServiceTest {

  private IncidentQueryService incidentQueryService;
  private AgentProposalRepository repository;
  private AuditRecorder auditRecorder;
  private ProposalRateLimiter rateLimiter;
  private AgentProposalService service;
  private Incident incident;

  @BeforeEach
  void setUp() {
    incidentQueryService = mock(IncidentQueryService.class);
    repository = mock(AgentProposalRepository.class);
    auditRecorder = mock(AuditRecorder.class);
    rateLimiter = mock(ProposalRateLimiter.class);
    when(rateLimiter.tryAcquire(any())).thenReturn(true);
    service =
        new AgentProposalService(
            incidentQueryService, repository, auditRecorder, new ObjectMapper(), rateLimiter);

    incident =
        Incident.detect(
            UUID.randomUUID(),
            "INC-0001",
            "Elevated latency",
            "desc",
            IncidentSeverity.SEV2,
            "prometheus",
            "checkout-api",
            Instant.now(),
            "corr-1",
            null);
    when(incidentQueryService.getOrThrow(incident.getId())).thenReturn(incident);
    when(repository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(repository.tryInsert(any()))
        .thenAnswer(inv -> Optional.of(((AgentProposalRow) inv.getArgument(0)).id()));
    when(repository.findById(any()))
        .thenAnswer(inv -> Optional.of(fixtureRow((UUID) inv.getArgument(0))));
  }

  private AgentProposalRow fixtureRow(UUID id) {
    return new AgentProposalRow(
        id,
        incident.getId(),
        ProposalActionType.ACKNOWLEDGE,
        Map.of(),
        "reason",
        List.of(),
        0,
        "hash",
        RiskClassification.LOW,
        "responder-1",
        "LOCAL_USER",
        "corr-1",
        "key-1",
        Instant.now(),
        Instant.now().plusSeconds(3600),
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
  }

  private ProposeActionCommand acknowledgeCommand(String idempotencyKey) {
    return new ProposeActionCommand(
        incident.getId(),
        ProposalActionType.ACKNOWLEDGE,
        Map.of(),
        "reason",
        List.of(),
        incident.getVersion(),
        "responder-1",
        "LOCAL_USER",
        "corr-1",
        idempotencyKey);
  }

  @Test
  void proposingAValidActionInsertsAPendingProposal() {
    AgentProposalRow row = service.propose(acknowledgeCommand("key-1"));
    assertThat(row.status()).isEqualTo(ProposalStatus.PENDING);
  }

  @Test
  void aRepeatedIdempotencyKeyReturnsTheExistingProposalWithoutInsertingAgain() {
    AgentProposalRow existing = fixtureRow(UUID.randomUUID());
    when(repository.findByIdempotencyKey("dup-key")).thenReturn(Optional.of(existing));

    AgentProposalRow result = service.propose(acknowledgeCommand("dup-key"));

    assertThat(result.id()).isEqualTo(existing.id());
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).tryInsert(any());
  }

  @Test
  void assignRequiresANonBlankAssigneeId() {
    ProposeActionCommand command =
        new ProposeActionCommand(
            incident.getId(),
            ProposalActionType.ASSIGN,
            Map.of(),
            "reason",
            List.of(),
            0,
            "responder-1",
            "LOCAL_USER",
            "corr-1",
            "key-2");
    assertThatThrownBy(() -> service.propose(command))
        .isInstanceOf(ProposalValidationException.class);
  }

  @Test
  void changeSeverityRejectsAnInvalidSeverityValue() {
    ProposeActionCommand command =
        new ProposeActionCommand(
            incident.getId(),
            ProposalActionType.CHANGE_SEVERITY,
            Map.of("severity", "SEV9"),
            "reason",
            List.of(),
            0,
            "responder-1",
            "LOCAL_USER",
            "corr-1",
            "key-3");
    assertThatThrownBy(() -> service.propose(command))
        .isInstanceOf(ProposalValidationException.class);
  }

  @Test
  void replayDeadLetterRejectsAnIneligibleTopic() {
    ProposeActionCommand command =
        new ProposeActionCommand(
            incident.getId(),
            ProposalActionType.REPLAY_DEAD_LETTER,
            Map.of("topic", "not.a.real.topic"),
            "reason",
            List.of(),
            0,
            "admin-1",
            "LOCAL_USER",
            "corr-1",
            "key-4");
    assertThatThrownBy(() -> service.propose(command))
        .isInstanceOf(ProposalValidationException.class);
  }

  @Test
  void aBlankReasonIsRejected() {
    ProposeActionCommand command =
        new ProposeActionCommand(
            incident.getId(),
            ProposalActionType.ACKNOWLEDGE,
            Map.of(),
            "  ",
            List.of(),
            0,
            "responder-1",
            "LOCAL_USER",
            "corr-1",
            "key-5");
    assertThatThrownBy(() -> service.propose(command))
        .isInstanceOf(ProposalValidationException.class);
  }

  @Test
  void exceedingTheRateLimitIsRejected() {
    when(rateLimiter.tryAcquire(any())).thenReturn(false);
    assertThatThrownBy(() -> service.propose(acknowledgeCommand("key-6")))
        .isInstanceOf(ProposalConflictException.class);
  }
}
