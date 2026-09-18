package com.sentinelops.incident.remediation.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.observability.Spans;
import com.sentinelops.incident.remediation.RemediationProperties;
import com.sentinelops.incident.remediation.adapters.CacheClearAdapter;
import com.sentinelops.incident.remediation.adapters.HealthCheckAdapter;
import com.sentinelops.incident.remediation.adapters.KubernetesRestartAdapter;
import com.sentinelops.incident.remediation.adapters.QueuePauseAdapter;
import com.sentinelops.incident.remediation.adapters.RemediationActionAdapter;
import com.sentinelops.incident.remediation.adapters.RemediationActionAdapterRegistry;
import com.sentinelops.incident.remediation.adapters.SimulatedCacheRepository;
import com.sentinelops.incident.remediation.adapters.SimulatedDeploymentRepository;
import com.sentinelops.incident.remediation.adapters.SimulatedDeploymentRow;
import com.sentinelops.incident.remediation.adapters.SimulatedQueueRepository;
import com.sentinelops.incident.remediation.lock.RemediationLockRepository;
import com.sentinelops.incident.remediation.policy.PolicyOutcome;
import com.sentinelops.incident.remediation.runbook.RemediationRunbookRepository;
import com.sentinelops.incident.remediation.runbook.RemediationRunbookRow;
import com.sentinelops.incident.remediation.runbook.RiskClassification;
import com.sentinelops.incident.remediation.runbook.RunbookYamlParser;
import io.micrometer.tracing.Tracer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

class RemediationExecutionSchedulerTest {

  private static final String SUCCEEDING_RUNBOOK_YAML =
      """
      slug: restart-and-check
      version: 1
      title: Restart and verify
      riskClassification: MEDIUM
      steps:
        - name: restart-deployment
          adapter: KUBERNETES_RESTART
          parameters:
            service: checkout-api
            environment: staging
        - name: verify-health
          adapter: HEALTH_CHECK
          parameters:
            service: checkout-api
            environment: staging
      rollbackSteps:
        - name: rollback-noop
          adapter: KUBERNETES_RESTART
          parameters:
            service: checkout-api
            environment: staging
      """;

  private static final String FAILING_STEP_RUNBOOK_YAML =
      """
      slug: pause-bad-queue
      version: 1
      title: Pause a disallowed queue
      riskClassification: MEDIUM
      steps:
        - name: pause-queue
          adapter: QUEUE_PAUSE
          parameters:
            name: not-allowlisted
      rollbackSteps:
        - name: resume-queue
          adapter: QUEUE_PAUSE
          parameters:
            name: notification-dispatch
      """;

  private RemediationExecutionRepository executionRepository;
  private final Map<UUID, RemediationExecutionRow> executionRows = new HashMap<>();
  private final Map<UUID, List<RemediationStepRow>> stepsByExecution = new HashMap<>();
  private RemediationStepRepository stepRepository;
  private SimulatedDeploymentRepository deploymentRepository;
  private RemediationExecutionScheduler scheduler;

  @BeforeEach
  void setUp() {
    executionRows.clear();
    stepsByExecution.clear();

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

    deploymentRepository = mock(SimulatedDeploymentRepository.class);
    RemediationActionAdapter restartAdapter = new KubernetesRestartAdapter(deploymentRepository);
    RemediationActionAdapter healthAdapter = new HealthCheckAdapter(deploymentRepository);
    RemediationActionAdapter cacheAdapter =
        new CacheClearAdapter(mock(SimulatedCacheRepository.class), properties);
    RemediationActionAdapter queueAdapter =
        new QueuePauseAdapter(mock(SimulatedQueueRepository.class), properties);
    RemediationActionAdapterRegistry adapterRegistry =
        new RemediationActionAdapterRegistry(
            List.of(restartAdapter, healthAdapter, cacheAdapter, queueAdapter));

    executionRepository = mock(RemediationExecutionRepository.class);
    when(executionRepository.findById(any()))
        .thenAnswer(inv -> Optional.ofNullable(executionRows.get((UUID) inv.getArgument(0))));
    when(executionRepository.transitionStatus(any(), any(), any(), any()))
        .thenAnswer(
            inv -> {
              UUID id = inv.getArgument(0);
              RemediationExecutionStatus next = inv.getArgument(2);
              RemediationExecutionRow current = executionRows.get(id);
              if (current == null) {
                return false;
              }
              executionRows.put(id, withStatus(current, next));
              return true;
            });

    stepRepository = mock(RemediationStepRepository.class);
    when(stepRepository.findByExecutionId(any()))
        .thenAnswer(
            inv -> new ArrayList<>(stepsByExecution.getOrDefault(inv.getArgument(0), List.of())));
    when(stepRepository.insertPending(any(), anyInt(), any(), any(), any(), anyBoolean()))
        .thenAnswer(this::fakeInsertPending);
    Mockito.doAnswer(this::fakeMarkRunning).when(stepRepository).markRunning(any(), any());
    Mockito.doAnswer(this::fakeMarkSucceeded)
        .when(stepRepository)
        .markSucceeded(any(), any(), any());
    Mockito.doAnswer(this::fakeMarkFailed).when(stepRepository).markFailed(any(), any(), any());

    RemediationRunbookRepository runbookRepository = mock(RemediationRunbookRepository.class);
    RemediationRunbookRow succeedingRunbook = runbookRowFor("restart-and-check");
    RemediationRunbookRow failingRunbook = runbookRowFor("pause-bad-queue");
    when(runbookRepository.findById(succeedingRunbook.id()))
        .thenReturn(Optional.of(succeedingRunbook));
    when(runbookRepository.findById(failingRunbook.id())).thenReturn(Optional.of(failingRunbook));

    AdapterCircuitBreakerRegistry circuitBreakers = new AdapterCircuitBreakerRegistry(properties);
    RemediationLockRepository lockRepository = mock(RemediationLockRepository.class);
    when(lockRepository.acquire(any(), any(), any(), any())).thenReturn(true);

    Spans spans = new Spans(mock(Tracer.class));
    AuditRecorder auditRecorder = mock(AuditRecorder.class);

    scheduler =
        new RemediationExecutionScheduler(
            executionRepository,
            stepRepository,
            runbookRepository,
            new RunbookYamlParser(),
            adapterRegistry,
            lockRepository,
            circuitBreakers,
            properties,
            auditRecorder,
            spans);
  }

  private RemediationRunbookRow runbookRow(String slug, RiskClassification risk, String yaml) {
    return new RemediationRunbookRow(
        UUID.randomUUID(), slug, 1, slug, risk, yaml, "hash", 1, true, Instant.now(), "system");
  }

  private UUID fakeInsertPending(InvocationOnMock inv) {
    UUID executionId = inv.getArgument(0);
    int stepIndex = inv.getArgument(1);
    String stepName = inv.getArgument(2);
    var adapterType =
        (com.sentinelops.incident.remediation.adapters.RemediationActionType) inv.getArgument(3);
    @SuppressWarnings("unchecked")
    Map<String, Object> parameters = (Map<String, Object>) inv.getArgument(4);
    boolean rollbackStep = inv.getArgument(5);
    UUID id = UUID.randomUUID();
    RemediationStepRow row =
        new RemediationStepRow(
            id,
            executionId,
            stepIndex,
            stepName,
            adapterType,
            parameters,
            rollbackStep,
            RemediationStepStatus.PENDING,
            0,
            null,
            null,
            null,
            null);
    stepsByExecution.computeIfAbsent(executionId, k -> new ArrayList<>()).add(row);
    return id;
  }

  private Object fakeMarkRunning(InvocationOnMock inv) {
    updateStep(
        inv.getArgument(0),
        row ->
            new RemediationStepRow(
                row.id(),
                row.executionId(),
                row.stepIndex(),
                row.stepName(),
                row.adapterType(),
                row.parameters(),
                row.rollbackStep(),
                RemediationStepStatus.RUNNING,
                row.attemptCount() + 1,
                Instant.now(),
                null,
                null,
                null));
    return null;
  }

  private Object fakeMarkSucceeded(InvocationOnMock inv) {
    @SuppressWarnings("unchecked")
    Map<String, Object> output = (Map<String, Object>) inv.getArgument(2);
    updateStep(
        inv.getArgument(0),
        row ->
            new RemediationStepRow(
                row.id(),
                row.executionId(),
                row.stepIndex(),
                row.stepName(),
                row.adapterType(),
                row.parameters(),
                row.rollbackStep(),
                RemediationStepStatus.SUCCEEDED,
                row.attemptCount(),
                row.startedAt(),
                Instant.now(),
                output,
                null));
    return null;
  }

  private Object fakeMarkFailed(InvocationOnMock inv) {
    String error = inv.getArgument(2);
    updateStep(
        inv.getArgument(0),
        row ->
            new RemediationStepRow(
                row.id(),
                row.executionId(),
                row.stepIndex(),
                row.stepName(),
                row.adapterType(),
                row.parameters(),
                row.rollbackStep(),
                RemediationStepStatus.FAILED,
                row.attemptCount(),
                row.startedAt(),
                Instant.now(),
                null,
                error));
    return null;
  }

  private void updateStep(
      UUID stepId, java.util.function.UnaryOperator<RemediationStepRow> updater) {
    for (var entry : stepsByExecution.entrySet()) {
      List<RemediationStepRow> list = entry.getValue();
      for (int i = 0; i < list.size(); i++) {
        if (list.get(i).id().equals(stepId)) {
          list.set(i, updater.apply(list.get(i)));
          return;
        }
      }
    }
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

  private RemediationExecutionRow newRunningExecution(UUID runbookId, String environment) {
    UUID id = UUID.randomUUID();
    RemediationExecutionRow row =
        new RemediationExecutionRow(
            id,
            runbookId,
            null,
            null,
            "idem-" + id,
            RemediationExecutionStatus.RUNNING,
            false,
            "responder-1",
            "corr-1",
            Map.of("environment", environment),
            Map.of(),
            PolicyOutcome.ALLOW,
            "auto-approved",
            1,
            0,
            false,
            false,
            null,
            null,
            null,
            null,
            Instant.now(),
            Instant.now(),
            Instant.now(),
            null,
            null);
    executionRows.put(id, row);
    return row;
  }

  @Test
  void successfulRunTransitionsToSucceeded() {
    when(deploymentRepository.find("checkout-api", "staging"))
        .thenReturn(
            Optional.of(
                new SimulatedDeploymentRow(
                    "checkout-api", "staging", 3, 1, List.of(), true, null, Instant.now())));

    RemediationRunbookRow runbook = runbookRowFor("restart-and-check");
    RemediationExecutionRow execution = newRunningExecution(runbook.id(), "staging");
    seedForwardSteps(
        execution.id(),
        "restart-deployment",
        com.sentinelops.incident.remediation.adapters.RemediationActionType.KUBERNETES_RESTART,
        Map.of("service", "checkout-api", "environment", "staging"));
    seedForwardSteps(
        execution.id(),
        "verify-health",
        com.sentinelops.incident.remediation.adapters.RemediationActionType.HEALTH_CHECK,
        Map.of("service", "checkout-api", "environment", "staging"));

    invokeRunOne(execution);

    assertThat(executionRows.get(execution.id()).status())
        .isEqualTo(RemediationExecutionStatus.SUCCEEDED);
  }

  @Test
  void failedStepTriggersAutomaticRollback() {
    RemediationRunbookRow runbook = runbookRowFor("pause-bad-queue");
    RemediationExecutionRow execution = newRunningExecution(runbook.id(), "staging");
    seedForwardSteps(
        execution.id(),
        "pause-queue",
        com.sentinelops.incident.remediation.adapters.RemediationActionType.QUEUE_PAUSE,
        Map.of("name", "not-allowlisted"));

    invokeRunOne(execution);

    RemediationExecutionRow result = executionRows.get(execution.id());
    assertThat(result.status()).isEqualTo(RemediationExecutionStatus.ROLLED_BACK);
    assertThat(stepsByExecution.get(execution.id())).anyMatch(RemediationStepRow::rollbackStep);
  }

  @Test
  void failedHealthCheckAfterSuccessfulStepsTriggersRollback() {
    when(deploymentRepository.find("checkout-api", "staging"))
        .thenReturn(
            Optional.of(
                new SimulatedDeploymentRow(
                    "checkout-api", "staging", 3, 1, List.of(), false, null, Instant.now())));

    RemediationRunbookRow runbook = runbookRowFor("restart-and-check");
    RemediationExecutionRow execution = newRunningExecution(runbook.id(), "staging");
    seedForwardSteps(
        execution.id(),
        "restart-deployment",
        com.sentinelops.incident.remediation.adapters.RemediationActionType.KUBERNETES_RESTART,
        Map.of("service", "checkout-api", "environment", "staging"));
    seedForwardSteps(
        execution.id(),
        "verify-health",
        com.sentinelops.incident.remediation.adapters.RemediationActionType.HEALTH_CHECK,
        Map.of("service", "checkout-api", "environment", "staging"));

    invokeRunOne(execution);

    RemediationExecutionRow result = executionRows.get(execution.id());
    assertThat(result.status()).isEqualTo(RemediationExecutionStatus.ROLLED_BACK);
  }

  private void seedForwardSteps(
      UUID executionId,
      String name,
      com.sentinelops.incident.remediation.adapters.RemediationActionType type,
      Map<String, Object> params) {
    List<RemediationStepRow> list =
        stepsByExecution.computeIfAbsent(executionId, k -> new ArrayList<>());
    RemediationStepRow row =
        new RemediationStepRow(
            UUID.randomUUID(),
            executionId,
            list.size(),
            name,
            type,
            params,
            false,
            RemediationStepStatus.PENDING,
            0,
            null,
            null,
            null,
            null);
    list.add(row);
  }

  private final Map<String, RemediationRunbookRow> runbooksBySlug = new HashMap<>();

  private RemediationRunbookRow runbookRowFor(String slug) {
    return runbooksBySlug.computeIfAbsent(
        slug,
        s -> {
          String yaml =
              s.equals("restart-and-check") ? SUCCEEDING_RUNBOOK_YAML : FAILING_STEP_RUNBOOK_YAML;
          return runbookRow(s, RiskClassification.MEDIUM, yaml);
        });
  }

  private void invokeRunOne(RemediationExecutionRow execution) {
    try {
      var method =
          RemediationExecutionScheduler.class.getDeclaredMethod(
              "runOne", RemediationExecutionRow.class);
      method.setAccessible(true);
      method.invoke(scheduler, execution);
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }
}
