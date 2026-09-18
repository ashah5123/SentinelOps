package com.sentinelops.incident.remediation.execution;

import com.sentinelops.incident.remediation.policy.PolicyOutcome;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemediationExecutionRow(
    UUID id,
    UUID runbookId,
    UUID incidentId,
    UUID proposalId,
    String idempotencyKey,
    RemediationExecutionStatus status,
    boolean dryRun,
    String requestedBy,
    String correlationId,
    Map<String, Object> parameters,
    Map<String, Object> blastRadius,
    PolicyOutcome policyDecision,
    String policyReason,
    int policyVersion,
    int requiredApprovals,
    boolean cancelRequested,
    boolean emergencyStop,
    Map<String, Object> healthBefore,
    Map<String, Object> healthAfter,
    String rollbackReason,
    String failureReason,
    Instant createdAt,
    Instant scheduledAt,
    Instant startedAt,
    Instant completedAt,
    Instant rolledBackAt) {}
