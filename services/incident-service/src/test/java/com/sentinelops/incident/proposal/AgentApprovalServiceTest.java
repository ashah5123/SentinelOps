package com.sentinelops.incident.proposal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.alerts.notification.NotificationRenderer;
import com.sentinelops.incident.alerts.notification.NotificationRepository;
import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.DeadLetterReplayService;
import com.sentinelops.incident.application.IncidentCommandService;
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

class AgentApprovalServiceTest {

  private AgentProposalRepository repository;
  private IncidentQueryService incidentQueryService;
  private IncidentCommandService incidentCommandService;
  private AuditRecorder auditRecorder;
  private AgentApprovalService service;
  private Incident incident;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    repository = mock(AgentProposalRepository.class);
    incidentQueryService = mock(IncidentQueryService.class);
    incidentCommandService = mock(IncidentCommandService.class);
    auditRecorder = mock(AuditRecorder.class);
    objectMapper = new ObjectMapper();
    service =
        new AgentApprovalService(
            repository,
            incidentQueryService,
            incidentCommandService,
            mock(DeadLetterReplayService.class),
            mock(NotificationRepository.class),
            mock(NotificationRenderer.class),
            auditRecorder,
            objectMapper);

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
  }

  private AgentProposalRow pendingAcknowledge(UUID id, String requestedBy) {
    long expectedVersion = incident.getVersion();
    String hash =
        ProposalContentHash.compute(
            objectMapper,
            incident.getId(),
            ProposalActionType.ACKNOWLEDGE,
            Map.of(),
            expectedVersion);
    return new AgentProposalRow(
        id,
        incident.getId(),
        ProposalActionType.ACKNOWLEDGE,
        Map.of(),
        "reason",
        List.of(),
        expectedVersion,
        hash,
        RiskClassification.LOW,
        requestedBy,
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

  private AgentProposalRow approved(AgentProposalRow pending) {
    return new AgentProposalRow(
        pending.id(),
        pending.incidentId(),
        pending.actionType(),
        pending.parameters(),
        pending.reason(),
        pending.evidenceReferences(),
        pending.expectedVersion(),
        pending.contentHash(),
        pending.riskClassification(),
        pending.requestedBy(),
        pending.requestedActorType(),
        pending.correlationId(),
        pending.idempotencyKey(),
        pending.createdAt(),
        pending.expiresAt(),
        ProposalStatus.APPROVED,
        "approver-1",
        Instant.now(),
        null,
        Instant.now().plusSeconds(900),
        false,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  @Test
  void anActorCannotApproveTheirOwnProposal() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForUpdate(id))
        .thenReturn(Optional.of(pendingAcknowledge(id, "responder-1")));

    assertThatThrownBy(() -> service.approveAndExecute(id, "responder-1", false, null, "corr-2"))
        .isInstanceOf(ProposalConflictException.class)
        .satisfies(
            e ->
                assertThat(((ProposalConflictException) e).reasonCode())
                    .isEqualTo("SELF_APPROVAL"));
  }

  @Test
  void aNonAdminCannotApproveADeadLetterReplay() {
    UUID id = UUID.randomUUID();
    AgentProposalRow proposal =
        new AgentProposalRow(
            id,
            incident.getId(),
            ProposalActionType.REPLAY_DEAD_LETTER,
            Map.of("topic", "x"),
            "reason",
            List.of(),
            0,
            "hash",
            RiskClassification.HIGH,
            "agent-1",
            "EVENT_CONSUMER",
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
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(proposal));

    assertThatThrownBy(() -> service.approveAndExecute(id, "responder-1", false, null, "corr-2"))
        .isInstanceOf(ProposalConflictException.class)
        .satisfies(
            e ->
                assertThat(((ProposalConflictException) e).reasonCode())
                    .isEqualTo("INSUFFICIENT_PERMISSION"));
  }

  @Test
  void anExpiredProposalCannotBeApproved() {
    UUID id = UUID.randomUUID();
    AgentProposalRow expired =
        new AgentProposalRow(
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
            Instant.now().minusSeconds(7200),
            Instant.now().minusSeconds(3600),
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
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(expired));

    assertThatThrownBy(() -> service.approveAndExecute(id, "approver-1", false, null, "corr-2"))
        .isInstanceOf(ProposalConflictException.class)
        .satisfies(
            e -> assertThat(((ProposalConflictException) e).reasonCode()).isEqualTo("EXPIRED"));
    verify(repository).markExpired(id);
  }

  @Test
  void approvingAndExecutingCallsTheRealCommandPathAndConsumesTheApproval() {
    UUID id = UUID.randomUUID();
    AgentProposalRow pending = pendingAcknowledge(id, "responder-1");
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(pending));
    when(repository.approve(eq(id), any(), any(), any(), any())).thenReturn(true);
    when(repository.consumeApproval(id)).thenReturn(true);
    when(repository.findById(id)).thenReturn(Optional.of(approved(pending)));

    AgentProposalRow result =
        service.approveAndExecute(id, "approver-1", false, "looks fine", "corr-2");

    verify(incidentCommandService)
        .transition(eq(incident.getId()), any(), any(), eq("corr-2"), eq("approver-1"));
    verify(repository).markExecuted(eq(id), any(), any());
    assertThat(result).isNotNull();
  }

  @Test
  void aReplayedApprovalIsRejectedTheSecondTime() {
    UUID id = UUID.randomUUID();
    AgentProposalRow pending = pendingAcknowledge(id, "responder-1");
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(pending));
    when(repository.approve(eq(id), any(), any(), any(), any())).thenReturn(true);
    when(repository.findById(id)).thenReturn(Optional.of(approved(pending)));
    // First call consumes the approval; the second (a replay) finds it already consumed.
    when(repository.consumeApproval(id)).thenReturn(true, false);

    service.approveAndExecute(id, "approver-1", false, null, "corr-2");

    // Simulate a replay: proposal is now APPROVED-but-already-executed in the real system, but
    // even if a caller resent the same approve call while status was still APPROVED, the second
    // consumeApproval() call must return false and block execution.
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(approved(pending)));
    assertThatThrownBy(() -> service.approveAndExecute(id, "approver-1", false, null, "corr-2"))
        .isInstanceOf(ProposalConflictException.class)
        .satisfies(
            e -> assertThat(((ProposalConflictException) e).reasonCode()).isEqualTo("NOT_PENDING"));
  }

  @Test
  void staleIncidentVersionBlocksExecution() {
    UUID id = UUID.randomUUID();
    // expectedVersion 0, but the incident's real version will not match after a bump.
    AgentProposalRow pending = pendingAcknowledge(id, "responder-1");
    AgentProposalRow withWrongExpectedVersion =
        new AgentProposalRow(
            pending.id(),
            pending.incidentId(),
            pending.actionType(),
            pending.parameters(),
            pending.reason(),
            pending.evidenceReferences(),
            99L,
            ProposalContentHash.compute(
                objectMapper, incident.getId(), ProposalActionType.ACKNOWLEDGE, Map.of(), 99L),
            pending.riskClassification(),
            pending.requestedBy(),
            pending.requestedActorType(),
            pending.correlationId(),
            pending.idempotencyKey(),
            pending.createdAt(),
            pending.expiresAt(),
            pending.status(),
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
    when(repository.findByIdForUpdate(id)).thenReturn(Optional.of(withWrongExpectedVersion));
    when(repository.approve(eq(id), any(), any(), any(), any())).thenReturn(true);
    when(repository.consumeApproval(id)).thenReturn(true);
    when(repository.findById(id)).thenReturn(Optional.of(approved(withWrongExpectedVersion)));

    assertThatThrownBy(() -> service.approveAndExecute(id, "approver-1", false, null, "corr-2"))
        .isInstanceOf(ProposalConflictException.class)
        .satisfies(
            e -> assertThat(((ProposalConflictException) e).reasonCode()).isEqualTo("STALE_STATE"));
    verify(incidentCommandService, org.mockito.Mockito.never())
        .transition(any(), any(), any(), any(), any());
  }

  @Test
  void rejectingRecordsAnAuditEvent() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdForUpdate(id))
        .thenReturn(Optional.of(pendingAcknowledge(id, "responder-1")));
    when(repository.reject(eq(id), any(), any(), any())).thenReturn(true);
    AgentProposalRow rejectedRow =
        new AgentProposalRow(
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
            ProposalStatus.REJECTED,
            null,
            null,
            null,
            null,
            false,
            "approver-1",
            Instant.now(),
            "not needed",
            null,
            null,
            null);
    when(repository.findById(id)).thenReturn(Optional.of(rejectedRow));

    AgentProposalRow result = service.reject(id, "approver-1", "not needed", "corr-3");

    assertThat(result.status()).isEqualTo(ProposalStatus.REJECTED);
    verify(auditRecorder)
        .record(
            eq(incident.getId()),
            eq("AGENT_PROPOSAL_REJECTED"),
            any(),
            eq("approver-1"),
            eq("corr-3"),
            any());
  }
}
