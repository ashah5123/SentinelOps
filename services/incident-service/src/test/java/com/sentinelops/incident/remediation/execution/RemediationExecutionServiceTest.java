package com.sentinelops.incident.remediation.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.remediation.RemediationProperties;
import com.sentinelops.incident.remediation.adapters.CacheClearAdapter;
import com.sentinelops.incident.remediation.adapters.HealthCheckAdapter;
import com.sentinelops.incident.remediation.adapters.RemediationActionAdapter;
import com.sentinelops.incident.remediation.adapters.RemediationActionAdapterRegistry;
import com.sentinelops.incident.remediation.adapters.SimulatedCacheRepository;
import com.sentinelops.incident.remediation.adapters.SimulatedDeploymentRepository;
import com.sentinelops.incident.remediation.policy.MaintenanceWindowEvaluator;
import com.sentinelops.incident.remediation.policy.PolicyEngine;
import com.sentinelops.incident.remediation.policy.PolicyOutcome;
import com.sentinelops.incident.remediation.runbook.RemediationRunbookRepository;
import com.sentinelops.incident.remediation.runbook.RemediationRunbookRow;
import com.sentinelops.incident.remediation.runbook.RiskClassification;
import com.sentinelops.incident.remediation.runbook.RunbookYamlParser;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Uses {@code executionRepository} as a lightweight in-memory fake (backed by {@code rows}) rather
 * than stubbing every read individually — {@code propose}/{@code approve}/etc. round-trip through
 * {@code findById} after every mutation, so a plain interaction mock would need near-identical
 * stubbing for every test.
 */
class RemediationExecutionServiceTest {

  private static final String LOW_RISK_YAML =
      """
      slug: test-clear-cache
      version: 1
      title: Clear test cache
      riskClassification: LOW
      steps:
        - name: clear-cache
          adapter: CACHE_CLEAR
          parameters:
            namespace: triage-search
      """;

  private final Map<UUID, RemediationExecutionRow> rows = new HashMap<>();

  private RemediationExecutionRepository executionRepository;
  private RemediationApprovalRepository approvalRepository;
  private RemediationRunbookRepository runbookRepository;
  private AuditRecorder auditRecorder;
  private RemediationExecutionService service;

  @BeforeEach
  void setUp() {
    rows.clear();
    executionRepository = mock(RemediationExecutionRepository.class);
    RemediationStepRepository stepRepository = mock(RemediationStepRepository.class);
    approvalRepository = mock(RemediationApprovalRepository.class);
    runbookRepository = mock(RemediationRunbookRepository.class);
    auditRecorder = mock(AuditRecorder.class);

    RemediationProperties properties =
        new RemediationProperties(
            List.of("triage-search"),
            List.of("notification-dispatch"),
            List.of("ping"),
            5,
            Duration.ofMinutes(5),
            Duration.ofSeconds(5),
            5,
            Duration.ofSeconds(10),
            new RemediationProperties.BlastRadius(5, List.of("production")),
            new RemediationProperties.CircuitBreakerSettings(3, Duration.ofMinutes(1)),
            new RemediationProperties.MaintenanceWindow(List.of("SATURDAY"), 2, 6, "UTC"));

    RemediationActionAdapter cacheAdapter =
        new CacheClearAdapter(mock(SimulatedCacheRepository.class), properties);
    RemediationActionAdapter healthAdapter =
        new HealthCheckAdapter(mock(SimulatedDeploymentRepository.class));
    RemediationActionAdapterRegistry adapterRegistry =
        new RemediationActionAdapterRegistry(List.of(cacheAdapter, healthAdapter));

    RemediationRunbookRow runbookRow =
        new RemediationRunbookRow(
            UUID.randomUUID(),
            "test-clear-cache",
            1,
            "Clear test cache",
            RiskClassification.LOW,
            LOW_RISK_YAML,
            "hash",
            1,
            true,
            Instant.now(),
            "system");
    when(runbookRepository.findActiveBySlug("test-clear-cache"))
        .thenReturn(Optional.of(runbookRow));

    when(executionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
    when(executionRepository.tryInsert(any()))
        .thenAnswer(
            inv -> {
              RemediationExecutionRow row = inv.getArgument(0);
              rows.put(row.id(), row);
              return Optional.of(row.id());
            });
    when(executionRepository.findById(any()))
        .thenAnswer(inv -> Optional.ofNullable(rows.get((UUID) inv.getArgument(0))));
    when(executionRepository.findByIdForUpdate(any()))
        .thenAnswer(inv -> Optional.ofNullable(rows.get((UUID) inv.getArgument(0))));
    when(executionRepository.countRecentFailures(any(), any())).thenReturn(0);
    when(executionRepository.transitionStatus(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              RemediationExecutionStatus next = inv.getArgument(2);
              RemediationExecutionRow current = rows.get(id);
              if (current == null) {
                return false;
              }
              rows.put(id, withStatus(current, next));
              return true;
            });

    service =
        new RemediationExecutionService(
            executionRepository,
            stepRepository,
            approvalRepository,
            runbookRepository,
            new RunbookYamlParser(),
            adapterRegistry,
            new RemediationExecutionStateMachine(),
            new PolicyEngine(properties),
            new MaintenanceWindowEvaluator(properties),
            mock(IncidentQueryService.class),
            auditRecorder);
  }

  private RemediationExecutionRow withStatus(
      RemediationExecutionRow row, RemediationExecutionStatus status) {
    return new RemediationExecutionRow(
        row.id(),
        row.runbookId(),
        row.incidentId(),
        row.proposalId(),
        row.idempotencyKey(),
        status,
        row.dryRun(),
        row.requestedBy(),
        row.correlationId(),
        row.parameters(),
        row.blastRadius(),
        row.policyDecision(),
        row.policyReason(),
        row.policyVersion(),
        row.requiredApprovals(),
        row.cancelRequested(),
        row.emergencyStop(),
        row.healthBefore(),
        row.healthAfter(),
        row.rollbackReason(),
        row.failureReason(),
        row.createdAt(),
        row.scheduledAt(),
        row.startedAt(),
        row.completedAt(),
        row.rolledBackAt());
  }

  private RemediationProposeRequest proposeRequest(Set<String> roles) {
    return new RemediationProposeRequest(
        "test-clear-cache",
        null,
        null,
        false,
        "responder-1",
        roles,
        "corr-1",
        UUID.randomUUID().toString());
  }

  @Test
  void lowRiskProposalIsAutoApprovedAndScheduled() {
    RemediationExecutionRow result = service.propose(proposeRequest(Set.of("RESPONDER")));

    assertThat(result.status()).isEqualTo(RemediationExecutionStatus.SCHEDULED);
    assertThat(result.policyDecision()).isEqualTo(PolicyOutcome.ALLOW);
  }

  @Test
  void deniedProposalIsRecordedAsDenied() {
    RemediationExecutionRow result = service.propose(proposeRequest(Set.of("VIEWER")));

    assertThat(result.status()).isEqualTo(RemediationExecutionStatus.DENIED);
    assertThat(result.policyDecision()).isEqualTo(PolicyOutcome.DENY);
  }

  @Test
  void retriedProposeWithSameIdempotencyKeyReturnsExistingExecution() {
    RemediationProposeRequest request = proposeRequest(Set.of("RESPONDER"));
    RemediationExecutionRow first = service.propose(request);

    when(executionRepository.findByIdempotencyKey(request.idempotencyKey()))
        .thenReturn(Optional.of(first));
    RemediationExecutionRow second = service.propose(request);

    assertThat(second.id()).isEqualTo(first.id());
  }

  @Test
  void approvingUnknownExecutionThrowsNotFound() {
    assertThatThrownBy(() -> service.approve(UUID.randomUUID(), "approver", "note", "corr"))
        .isInstanceOf(RemediationNotFoundException.class);
  }

  @Test
  void selfApprovalIsRejected() {
    RemediationExecutionRow pending = restrictedPendingExecution(1);
    assertThatThrownBy(() -> service.approve(pending.id(), "responder-1", "note", "corr"))
        .isInstanceOf(RemediationConflictException.class)
        .hasMessageContaining("cannot approve their own");
  }

  @Test
  void approvalBelowRequiredCountLeavesExecutionPending() {
    RemediationExecutionRow pending = restrictedPendingExecution(2);
    when(approvalRepository.tryRecordApproval(any(), any(), any(), any())).thenReturn(true);
    when(approvalRepository.countDistinctApprovers(pending.id())).thenReturn(1);

    RemediationExecutionRow result = service.approve(pending.id(), "approver-2", "note", "corr");

    assertThat(result.status()).isEqualTo(RemediationExecutionStatus.PROPOSED);
    verify(executionRepository, never())
        .transitionStatus(eq(pending.id()), any(), eq(RemediationExecutionStatus.APPROVED), any());
  }

  @Test
  void secondApprovalSchedulesExecution() {
    RemediationExecutionRow pending = restrictedPendingExecution(2);
    when(approvalRepository.tryRecordApproval(any(), any(), any(), any())).thenReturn(true);
    when(approvalRepository.countDistinctApprovers(pending.id())).thenReturn(2);

    RemediationExecutionRow result = service.approve(pending.id(), "approver-2", "note", "corr");

    assertThat(result.status()).isEqualTo(RemediationExecutionStatus.SCHEDULED);
  }

  @Test
  void duplicateApprovalByTheSameActorIsRejected() {
    RemediationExecutionRow pending = restrictedPendingExecution(2);
    when(approvalRepository.tryRecordApproval(any(), any(), any(), any())).thenReturn(false);

    assertThatThrownBy(() -> service.approve(pending.id(), "approver-2", "note", "corr"))
        .isInstanceOf(RemediationConflictException.class)
        .hasMessageContaining("already approved");
  }

  @Test
  void emergencyStopRequiresAdmin() {
    assertThatThrownBy(() -> service.emergencyStop(UUID.randomUUID(), false, "actor", "corr"))
        .isInstanceOf(RemediationConflictException.class)
        .hasMessageContaining("administrator");
  }

  @Test
  void cancelWhileRunningSetsCancelRequestedFlagInsteadOfTransitioning() {
    RemediationExecutionRow pending = restrictedPendingExecution(1);
    rows.put(pending.id(), withStatus(pending, RemediationExecutionStatus.RUNNING));

    service.cancel(pending.id(), "operator-1", "corr");

    verify(executionRepository).requestCancel(pending.id());
    verify(executionRepository, never())
        .transitionStatus(eq(pending.id()), any(), eq(RemediationExecutionStatus.CANCELLED), any());
  }

  /** A REQUIRE_APPROVAL execution: LOW risk in the restricted "production" environment. */
  private RemediationExecutionRow restrictedPendingExecution(int requiredApprovals) {
    UUID id = UUID.randomUUID();
    RemediationExecutionRow row =
        new RemediationExecutionRow(
            id,
            UUID.randomUUID(),
            null,
            null,
            "idem-" + id,
            RemediationExecutionStatus.PROPOSED,
            false,
            "responder-1",
            "corr-1",
            Map.of(),
            Map.of(),
            PolicyOutcome.REQUIRE_APPROVAL,
            "reason",
            1,
            requiredApprovals,
            false,
            false,
            null,
            null,
            null,
            null,
            Instant.now(),
            null,
            null,
            null,
            null);
    rows.put(id, row);
    return row;
  }
}
