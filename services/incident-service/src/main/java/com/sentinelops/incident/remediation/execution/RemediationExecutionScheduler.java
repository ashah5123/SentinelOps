package com.sentinelops.incident.remediation.execution;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.observability.Spans;
import com.sentinelops.incident.remediation.RemediationProperties;
import com.sentinelops.incident.remediation.adapters.AdapterResult;
import com.sentinelops.incident.remediation.adapters.RemediationActionAdapterRegistry;
import com.sentinelops.incident.remediation.adapters.RemediationActionType;
import com.sentinelops.incident.remediation.lock.RemediationLockRepository;
import com.sentinelops.incident.remediation.runbook.RemediationRunbookRepository;
import com.sentinelops.incident.remediation.runbook.RunbookDefinition;
import com.sentinelops.incident.remediation.runbook.RunbookStepDefinition;
import com.sentinelops.incident.remediation.runbook.RunbookYamlParser;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs SCHEDULED executions to completion (section: "Build the remediation engine"): claims a
 * batch, runs each step in order with retry/backoff/circuit-breaker, checks {@code
 * cancel_requested}/{@code emergency_stop} between steps, captures health before/after, and
 * automatically runs the runbook's rollback steps on any forward-step failure or a failed
 * post-execution health check. Restarting the service loses nothing — schedules and step state live
 * in the database, and the distributed lock plus the state machine's guarded transitions mean
 * concurrent scheduler instances never run (or double-run) the same execution.
 *
 * <p>Known simplification (sandboxed/local-demo scope, documented rather than hidden): each claimed
 * execution runs to completion in one scheduler tick rather than across many short transactions
 * like {@code OutboxPublisher} — acceptable here because every adapter call is a fast
 * local/simulated operation, never a real long-running external call.
 */
@Component
public class RemediationExecutionScheduler {

  private static final Logger log = LoggerFactory.getLogger(RemediationExecutionScheduler.class);
  private static final long MAX_BACKOFF_SLEEP_MILLIS = 50;

  private final RemediationExecutionRepository executionRepository;
  private final RemediationStepRepository stepRepository;
  private final RemediationRunbookRepository runbookRepository;
  private final RunbookYamlParser runbookParser;
  private final RemediationActionAdapterRegistry adapterRegistry;
  private final RemediationLockRepository lockRepository;
  private final AdapterCircuitBreakerRegistry circuitBreakers;
  private final RemediationProperties properties;
  private final AuditRecorder auditRecorder;
  private final Spans spans;

  public RemediationExecutionScheduler(
      RemediationExecutionRepository executionRepository,
      RemediationStepRepository stepRepository,
      RemediationRunbookRepository runbookRepository,
      RunbookYamlParser runbookParser,
      RemediationActionAdapterRegistry adapterRegistry,
      RemediationLockRepository lockRepository,
      AdapterCircuitBreakerRegistry circuitBreakers,
      RemediationProperties properties,
      AuditRecorder auditRecorder,
      Spans spans) {
    this.executionRepository = executionRepository;
    this.stepRepository = stepRepository;
    this.runbookRepository = runbookRepository;
    this.runbookParser = runbookParser;
    this.adapterRegistry = adapterRegistry;
    this.lockRepository = lockRepository;
    this.circuitBreakers = circuitBreakers;
    this.properties = properties;
    this.auditRecorder = auditRecorder;
    this.spans = spans;
  }

  @Scheduled(fixedDelayString = "${sentinelops.remediation.scheduler-polling-interval}")
  public void runDueExecutions() {
    for (RemediationExecutionRow execution : claimBatch()) {
      spans.inSpan("remediation.execution.run", Map.of(), () -> runOne(execution));
    }
  }

  private List<RemediationExecutionRow> claimBatch() {
    List<RemediationExecutionRow> due =
        executionRepository.claimDueBatch(properties.schedulerBatchSize());
    List<RemediationExecutionRow> claimed = new ArrayList<>();
    Instant now = Instant.now();
    for (RemediationExecutionRow row : due) {
      String resourceKey = resourceKeyFor(row);
      if (!lockRepository.acquire(
          resourceKey, row.id(), now, now.plus(properties.lockLeaseDuration()))) {
        continue;
      }
      if (!executionRepository.transitionStatus(
          row.id(),
          RemediationExecutionStatus.SCHEDULED,
          RemediationExecutionStatus.RUNNING,
          now)) {
        lockRepository.release(resourceKey, row.id());
        continue;
      }
      claimed.add(executionRepository.findById(row.id()).orElseThrow());
    }
    return claimed;
  }

  private void runOne(RemediationExecutionRow execution) {
    String resourceKey = resourceKeyFor(execution);
    try {
      var runbookRow = runbookRepository.findById(execution.runbookId()).orElseThrow();
      RunbookDefinition definition = runbookParser.parse(runbookRow.definitionYaml());

      executionRepository.recordHealthBefore(execution.id(), captureHealth(definition));

      String failureReason = runForwardSteps(execution, definition);
      Map<String, Object> healthAfter = captureHealth(definition);
      executionRepository.recordHealthAfter(execution.id(), healthAfter);

      Instant now = Instant.now();
      if (failureReason == null && isHealthy(healthAfter)) {
        executionRepository.transitionStatus(
            execution.id(),
            RemediationExecutionStatus.RUNNING,
            RemediationExecutionStatus.SUCCEEDED,
            now);
        audit(execution, "REMEDIATION_SUCCEEDED", Map.of());
      } else {
        String reason =
            failureReason != null ? failureReason : "Post-execution health check failed";
        executionRepository.recordFailureReason(execution.id(), reason);
        executionRepository.transitionStatus(
            execution.id(),
            RemediationExecutionStatus.RUNNING,
            RemediationExecutionStatus.FAILED,
            now);
        audit(execution, "REMEDIATION_FAILED", Map.of("reason", reason));
        runRollback(execution, definition);
      }
    } catch (RuntimeException e) {
      log.error("Remediation execution {} failed unexpectedly", execution.id(), e);
      executionRepository.recordFailureReason(
          execution.id(), e.getClass().getSimpleName() + ": " + e.getMessage());
      executionRepository.transitionStatus(
          execution.id(),
          RemediationExecutionStatus.RUNNING,
          RemediationExecutionStatus.FAILED,
          Instant.now());
    } finally {
      lockRepository.release(resourceKey, execution.id());
    }
  }

  /**
   * Returns null on full success, or a human-readable reason on the first step that fails/is
   * cancelled.
   */
  private String runForwardSteps(RemediationExecutionRow execution, RunbookDefinition definition) {
    List<RemediationStepRow> steps =
        stepRepository.findByExecutionId(execution.id()).stream()
            .filter(s -> !s.rollbackStep())
            .toList();
    for (RemediationStepRow step : steps) {
      RemediationExecutionRow fresh = executionRepository.findById(execution.id()).orElseThrow();
      if (fresh.emergencyStop()) {
        return "Emergency stop requested";
      }
      if (fresh.cancelRequested()) {
        return "Cancellation requested";
      }
      if (!runStepWithRetry(execution, step, definition.steps())) {
        return "Step '" + step.stepName() + "' failed";
      }
    }
    return null;
  }

  private void runRollback(RemediationExecutionRow execution, RunbookDefinition definition) {
    if (definition.rollbackSteps().isEmpty()) {
      executionRepository.recordRollbackReason(
          execution.id(), "No rollback steps defined for this runbook");
      return;
    }
    int index = 0;
    for (RunbookStepDefinition step : definition.rollbackSteps()) {
      stepRepository.insertPending(
          execution.id(), index++, step.name(), step.adapterType(), step.parameters(), true);
    }
    List<RemediationStepRow> rollbackRows =
        stepRepository.findByExecutionId(execution.id()).stream()
            .filter(RemediationStepRow::rollbackStep)
            .toList();

    boolean rollbackOk = true;
    for (RemediationStepRow step : rollbackRows) {
      if (!runStepWithRetry(execution, step, definition.rollbackSteps())) {
        rollbackOk = false;
        break;
      }
    }
    if (rollbackOk) {
      executionRepository.recordRollbackReason(
          execution.id(), "Automatic rollback after failed step or health check");
      executionRepository.transitionStatus(
          execution.id(),
          RemediationExecutionStatus.FAILED,
          RemediationExecutionStatus.ROLLED_BACK,
          Instant.now());
      audit(execution, "REMEDIATION_ROLLED_BACK", Map.of());
    } else {
      executionRepository.recordRollbackReason(
          execution.id(), "Automatic rollback attempted but a rollback step also failed");
      audit(execution, "REMEDIATION_ROLLBACK_FAILED", Map.of());
    }
  }

  private boolean runStepWithRetry(
      RemediationExecutionRow execution,
      RemediationStepRow step,
      List<RunbookStepDefinition> definitions) {
    RunbookStepDefinition stepDef =
        definitions.stream()
            .filter(d -> d.name().equals(step.stepName()))
            .findFirst()
            .orElseThrow();
    var breaker = circuitBreakers.forType(step.adapterType());
    long backoff = stepDef.backoffInitialMillis();

    for (int attempt = 0; attempt <= stepDef.maxRetries(); attempt++) {
      if (!breaker.allowRequest()) {
        stepRepository.markFailed(
            step.id(), Instant.now(), "circuit breaker open for " + step.adapterType());
        return false;
      }
      stepRepository.markRunning(step.id(), Instant.now());
      AdapterResult result = invokeAdapter(execution, step);

      if (result.success()) {
        breaker.recordSuccess();
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("message", result.message());
        output.put("affectedResources", result.affectedResources());
        stepRepository.markSucceeded(step.id(), Instant.now(), output);
        return true;
      }

      breaker.recordFailure();
      if (attempt == stepDef.maxRetries()) {
        stepRepository.markFailed(step.id(), Instant.now(), result.message());
        return false;
      }
      sleepBounded(backoff);
      backoff = (long) (backoff * stepDef.backoffMultiplier());
    }
    return false;
  }

  private AdapterResult invokeAdapter(RemediationExecutionRow execution, RemediationStepRow step) {
    var adapter = adapterRegistry.forType(step.adapterType());
    try {
      return execution.dryRun()
          ? adapter.plan(step.parameters())
          : adapter.execute(step.parameters());
    } catch (RuntimeException e) {
      return AdapterResult.failure(e.getClass().getSimpleName() + ": " + e.getMessage(), List.of());
    }
  }

  private void sleepBounded(long requestedMillis) {
    try {
      Thread.sleep(Math.min(requestedMillis, MAX_BACKOFF_SLEEP_MILLIS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private Map<String, Object> captureHealth(RunbookDefinition definition) {
    for (RunbookStepDefinition step : definition.steps()) {
      Object service = step.parameters().get("service");
      Object environment = step.parameters().get("environment");
      if (service instanceof String s && environment instanceof String e) {
        AdapterResult result =
            adapterRegistry
                .forType(RemediationActionType.HEALTH_CHECK)
                .plan(Map.of("service", s, "environment", e));
        return Map.of("healthy", result.success(), "message", result.message());
      }
    }
    return Map.of(
        "healthy", true, "message", "no health-checkable target declared in this runbook");
  }

  private boolean isHealthy(Map<String, Object> health) {
    Object healthy = health.get("healthy");
    return !(healthy instanceof Boolean b) || b;
  }

  private String resourceKeyFor(RemediationExecutionRow execution) {
    Object environment = execution.parameters().get("environment");
    return "runbook:" + execution.runbookId() + "|environment:" + environment;
  }

  private void audit(
      RemediationExecutionRow execution, String action, Map<String, Object> extraMetadata) {
    Map<String, Object> metadata = new LinkedHashMap<>(extraMetadata);
    metadata.put("executionId", execution.id().toString());
    auditRecorder.record(
        execution.incidentId(),
        action,
        ActorType.SYSTEM,
        "remediation-scheduler",
        "remediation-" + execution.id(),
        metadata);
  }
}
