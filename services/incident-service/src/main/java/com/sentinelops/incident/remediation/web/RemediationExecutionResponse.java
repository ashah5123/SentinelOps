package com.sentinelops.incident.remediation.web;

import com.sentinelops.incident.remediation.execution.RemediationExecutionRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemediationExecutionResponse(
    UUID id,
    UUID runbookId,
    UUID incidentId,
    UUID proposalId,
    String status,
    boolean dryRun,
    String requestedBy,
    Map<String, Object> parameters,
    Map<String, Object> blastRadius,
    String policyDecision,
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
    Instant rolledBackAt) {

  public static RemediationExecutionResponse from(RemediationExecutionRow row) {
    return new RemediationExecutionResponse(
        row.id(),
        row.runbookId(),
        row.incidentId(),
        row.proposalId(),
        row.status().name(),
        row.dryRun(),
        row.requestedBy(),
        row.parameters(),
        row.blastRadius(),
        row.policyDecision().name(),
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
}
