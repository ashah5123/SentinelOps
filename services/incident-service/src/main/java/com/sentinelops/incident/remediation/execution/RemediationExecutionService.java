package com.sentinelops.incident.remediation.execution;

import com.sentinelops.incident.application.AuditRecorder;
import com.sentinelops.incident.application.IncidentQueryService;
import com.sentinelops.incident.domain.ActorType;
import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.remediation.adapters.AdapterResult;
import com.sentinelops.incident.remediation.adapters.RemediationActionAdapterRegistry;
import com.sentinelops.incident.remediation.policy.MaintenanceWindowEvaluator;
import com.sentinelops.incident.remediation.policy.PolicyDecision;
import com.sentinelops.incident.remediation.policy.PolicyEngine;
import com.sentinelops.incident.remediation.policy.PolicyOutcome;
import com.sentinelops.incident.remediation.policy.PolicyRequest;
import com.sentinelops.incident.remediation.runbook.RemediationRunbookRepository;
import com.sentinelops.incident.remediation.runbook.RemediationRunbookRow;
import com.sentinelops.incident.remediation.runbook.RunbookDefinition;
import com.sentinelops.incident.remediation.runbook.RunbookNotFoundException;
import com.sentinelops.incident.remediation.runbook.RunbookStepDefinition;
import com.sentinelops.incident.remediation.runbook.RunbookYamlParser;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates the propose/approve/reject/cancel/emergency-stop lifecycle of a remediation
 * execution (section: "Build the remediation engine"). Never runs a step itself — that is {@code
 * RemediationExecutionScheduler}'s job, once this service has moved an execution to SCHEDULED.
 */
@Service
public class RemediationExecutionService {

  private static final Duration RECENT_FAILURE_LOOKBACK = Duration.ofHours(1);

  private final RemediationExecutionRepository executionRepository;
  private final RemediationStepRepository stepRepository;
  private final RemediationApprovalRepository approvalRepository;
  private final RemediationRunbookRepository runbookRepository;
  private final RunbookYamlParser runbookParser;
  private final RemediationActionAdapterRegistry adapterRegistry;
  private final RemediationExecutionStateMachine stateMachine;
  private final PolicyEngine policyEngine;
  private final MaintenanceWindowEvaluator maintenanceWindowEvaluator;
  private final IncidentQueryService incidentQueryService;
  private final AuditRecorder auditRecorder;

  public RemediationExecutionService(
      RemediationExecutionRepository executionRepository,
      RemediationStepRepository stepRepository,
      RemediationApprovalRepository approvalRepository,
      RemediationRunbookRepository runbookRepository,
      RunbookYamlParser runbookParser,
      RemediationActionAdapterRegistry adapterRegistry,
      RemediationExecutionStateMachine stateMachine,
      PolicyEngine policyEngine,
      MaintenanceWindowEvaluator maintenanceWindowEvaluator,
      IncidentQueryService incidentQueryService,
      AuditRecorder auditRecorder) {
    this.executionRepository = executionRepository;
    this.stepRepository = stepRepository;
    this.approvalRepository = approvalRepository;
    this.runbookRepository = runbookRepository;
    this.runbookParser = runbookParser;
    this.adapterRegistry = adapterRegistry;
    this.stateMachine = stateMachine;
    this.policyEngine = policyEngine;
    this.maintenanceWindowEvaluator = maintenanceWindowEvaluator;
    this.incidentQueryService = incidentQueryService;
    this.auditRecorder = auditRecorder;
  }

  @Transactional
  public RemediationExecutionRow propose(RemediationProposeRequest request) {
    var existing = executionRepository.findByIdempotencyKey(request.idempotencyKey());
    if (existing.isPresent()) {
      return existing.get();
    }

    RemediationRunbookRow runbookRow =
        runbookRepository
            .findActiveBySlug(request.runbookSlug())
            .orElseThrow(() -> new RunbookNotFoundException(request.runbookSlug()));
    RunbookDefinition definition = runbookParser.parse(runbookRow.definitionYaml());

    String environment = extractEnvironment(definition);
    Set<String> affectedResources = computeAffectedResources(definition);

    IncidentSeverity severity =
        request.incidentId() == null
            ? null
            : incidentQueryService.getOrThrow(request.incidentId()).getSeverity();

    int recentFailures =
        executionRepository.countRecentFailures(
            runbookRow.id(), Instant.now().minus(RECENT_FAILURE_LOOKBACK));

    PolicyDecision decision =
        policyEngine.evaluate(
            new PolicyRequest(
                request.requestedByRoles(),
                severity,
                environment,
                runbookRow.riskClassification(),
                affectedResources.size(),
                maintenanceWindowEvaluator.isActiveNow(),
                recentFailures));

    UUID executionId = UUID.randomUUID();
    RemediationExecutionRow row =
        new RemediationExecutionRow(
            executionId,
            runbookRow.id(),
            request.incidentId(),
            request.proposalId(),
            request.idempotencyKey(),
            RemediationExecutionStatus.PROPOSED,
            request.dryRun(),
            request.requestedBy(),
            request.correlationId(),
            Map.of("runbookSlug", request.runbookSlug(), "environment", environment),
            Map.of("resourceCount", affectedResources.size(), "resources", affectedResources),
            decision.outcome(),
            decision.reason(),
            decision.policyVersion(),
            decision.requiredApprovals(),
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

    UUID insertedId =
        executionRepository
            .tryInsert(row)
            .orElseGet(
                () ->
                    executionRepository
                        .findByIdempotencyKey(request.idempotencyKey())
                        .orElseThrow()
                        .id());
    if (!insertedId.equals(executionId)) {
      return executionRepository.findById(insertedId).orElseThrow();
    }

    int stepIndex = 0;
    for (RunbookStepDefinition step : definition.steps()) {
      stepRepository.insertPending(
          executionId, stepIndex++, step.name(), step.adapterType(), step.parameters(), false);
    }

    auditRecorder.record(
        request.incidentId(),
        "REMEDIATION_PROPOSED",
        ActorType.LOCAL_USER,
        request.requestedBy(),
        request.correlationId(),
        Map.of(
            "executionId", executionId.toString(),
            "runbookSlug", request.runbookSlug(),
            "policyDecision", decision.outcome().name(),
            "policyReason", decision.reason()));

    if (decision.outcome() == PolicyOutcome.DENY) {
      executionRepository.transitionStatus(
          executionId,
          RemediationExecutionStatus.PROPOSED,
          RemediationExecutionStatus.DENIED,
          Instant.now());
      auditRecorder.record(
          request.incidentId(),
          "REMEDIATION_DENIED",
          ActorType.SYSTEM,
          "system",
          request.correlationId(),
          Map.of("executionId", executionId.toString(), "reason", decision.reason()));
    } else if (decision.outcome() == PolicyOutcome.ALLOW) {
      autoApproveAndSchedule(executionId, request.incidentId(), request.correlationId());
    }

    return executionRepository.findById(executionId).orElseThrow();
  }

  @Transactional
  public RemediationExecutionRow approve(
      UUID executionId, String approverActorId, String note, String correlationId) {
    RemediationExecutionRow row =
        executionRepository
            .findByIdForUpdate(executionId)
            .orElseThrow(() -> new RemediationNotFoundException(executionId));
    if (row.status() != RemediationExecutionStatus.PROPOSED) {
      throw new RemediationConflictException(
          "NOT_PROPOSED", "Execution is not awaiting approval: " + row.status());
    }
    if (row.requestedBy().equals(approverActorId)) {
      throw new RemediationConflictException(
          "SELF_APPROVAL", "An actor cannot approve their own remediation request");
    }

    boolean recorded =
        approvalRepository.tryRecordApproval(executionId, approverActorId, Instant.now(), note);
    if (!recorded) {
      throw new RemediationConflictException(
          "ALREADY_APPROVED", "This actor has already approved this execution");
    }
    auditRecorder.record(
        row.incidentId(),
        "REMEDIATION_APPROVAL_RECORDED",
        ActorType.LOCAL_USER,
        approverActorId,
        correlationId,
        Map.of("executionId", executionId.toString()));

    int approvals = approvalRepository.countDistinctApprovers(executionId);
    if (approvals >= row.requiredApprovals()) {
      scheduleApproved(executionId, row.incidentId(), correlationId);
    }
    return executionRepository.findById(executionId).orElseThrow();
  }

  @Transactional
  public RemediationExecutionRow reject(UUID executionId, String rejectedBy, String correlationId) {
    RemediationExecutionRow row =
        executionRepository
            .findByIdForUpdate(executionId)
            .orElseThrow(() -> new RemediationNotFoundException(executionId));
    if (row.status() != RemediationExecutionStatus.PROPOSED) {
      throw new RemediationConflictException(
          "NOT_PROPOSED", "Execution is not awaiting approval: " + row.status());
    }
    stateMachine.assertTransitionAllowed(row.status(), RemediationExecutionStatus.DENIED);
    boolean ok =
        executionRepository.transitionStatus(
            executionId, row.status(), RemediationExecutionStatus.DENIED, Instant.now());
    if (!ok) {
      throw new RemediationConflictException(
          "CONCURRENT_MODIFICATION", "Execution was modified concurrently");
    }
    auditRecorder.record(
        row.incidentId(),
        "REMEDIATION_REJECTED",
        ActorType.LOCAL_USER,
        rejectedBy,
        correlationId,
        Map.of("executionId", executionId.toString()));
    return executionRepository.findById(executionId).orElseThrow();
  }

  @Transactional
  public RemediationExecutionRow cancel(
      UUID executionId, String cancelledBy, String correlationId) {
    RemediationExecutionRow row =
        executionRepository
            .findByIdForUpdate(executionId)
            .orElseThrow(() -> new RemediationNotFoundException(executionId));

    if (row.status() == RemediationExecutionStatus.RUNNING) {
      executionRepository.requestCancel(executionId);
      auditRecorder.record(
          row.incidentId(),
          "REMEDIATION_CANCEL_REQUESTED",
          ActorType.LOCAL_USER,
          cancelledBy,
          correlationId,
          Map.of("executionId", executionId.toString()));
      return executionRepository.findById(executionId).orElseThrow();
    }

    if (!stateMachine.isTransitionAllowed(row.status(), RemediationExecutionStatus.CANCELLED)) {
      throw new RemediationConflictException(
          "NOT_CANCELLABLE", "Execution cannot be cancelled from state: " + row.status());
    }
    boolean ok =
        executionRepository.transitionStatus(
            executionId, row.status(), RemediationExecutionStatus.CANCELLED, Instant.now());
    if (!ok) {
      throw new RemediationConflictException(
          "CONCURRENT_MODIFICATION", "Execution was modified concurrently");
    }
    auditRecorder.record(
        row.incidentId(),
        "REMEDIATION_CANCELLED",
        ActorType.LOCAL_USER,
        cancelledBy,
        correlationId,
        Map.of("executionId", executionId.toString()));
    return executionRepository.findById(executionId).orElseThrow();
  }

  @Transactional
  public RemediationExecutionRow emergencyStop(
      UUID executionId, boolean actorIsAdmin, String actorId, String correlationId) {
    if (!actorIsAdmin) {
      throw new RemediationConflictException(
          "INSUFFICIENT_PERMISSION", "Only an administrator may trigger an emergency stop");
    }
    RemediationExecutionRow row =
        executionRepository
            .findByIdForUpdate(executionId)
            .orElseThrow(() -> new RemediationNotFoundException(executionId));
    if (row.status() != RemediationExecutionStatus.RUNNING
        && row.status() != RemediationExecutionStatus.SCHEDULED) {
      throw new RemediationConflictException(
          "NOT_STOPPABLE", "Execution is not running or scheduled: " + row.status());
    }
    executionRepository.setEmergencyStop(executionId);
    auditRecorder.record(
        row.incidentId(),
        "REMEDIATION_EMERGENCY_STOP",
        ActorType.LOCAL_USER,
        actorId,
        correlationId,
        Map.of("executionId", executionId.toString()));
    return executionRepository.findById(executionId).orElseThrow();
  }

  private void autoApproveAndSchedule(UUID executionId, UUID incidentId, String correlationId) {
    executionRepository.transitionStatus(
        executionId,
        RemediationExecutionStatus.PROPOSED,
        RemediationExecutionStatus.APPROVED,
        Instant.now());
    auditRecorder.record(
        incidentId,
        "REMEDIATION_AUTO_APPROVED",
        ActorType.SYSTEM,
        "system",
        correlationId,
        Map.of("executionId", executionId.toString()));
    scheduleApproved(executionId, incidentId, correlationId);
  }

  private void scheduleApproved(UUID executionId, UUID incidentId, String correlationId) {
    executionRepository.transitionStatus(
        executionId,
        RemediationExecutionStatus.APPROVED,
        RemediationExecutionStatus.SCHEDULED,
        Instant.now());
    auditRecorder.record(
        incidentId,
        "REMEDIATION_SCHEDULED",
        ActorType.SYSTEM,
        "system",
        correlationId,
        Map.of("executionId", executionId.toString()));
  }

  private String extractEnvironment(RunbookDefinition definition) {
    for (RunbookStepDefinition step : definition.steps()) {
      Object env = step.parameters().get("environment");
      if (env instanceof String s) {
        return s;
      }
    }
    return "unknown";
  }

  private Set<String> computeAffectedResources(RunbookDefinition definition) {
    Set<String> resources = new LinkedHashSet<>();
    for (RunbookStepDefinition step : definition.steps()) {
      AdapterResult planned = adapterRegistry.forType(step.adapterType()).plan(step.parameters());
      resources.addAll(planned.affectedResources());
    }
    return resources;
  }
}
