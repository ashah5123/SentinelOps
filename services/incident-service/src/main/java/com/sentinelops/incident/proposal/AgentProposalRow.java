package com.sentinelops.incident.proposal;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A persisted row from {@code incidents.agent_proposals}. */
public record AgentProposalRow(
    UUID id,
    UUID incidentId,
    ProposalActionType actionType,
    Map<String, Object> parameters,
    String reason,
    List<String> evidenceReferences,
    long expectedVersion,
    String contentHash,
    RiskClassification riskClassification,
    String requestedBy,
    String requestedActorType,
    String correlationId,
    String idempotencyKey,
    Instant createdAt,
    Instant expiresAt,
    ProposalStatus status,
    String approvedBy,
    Instant approvedAt,
    String reviewNote,
    Instant approvalExpiresAt,
    boolean approvalConsumed,
    String rejectedBy,
    Instant rejectedAt,
    String rejectionNote,
    Instant executedAt,
    String executionResult,
    String executionError) {

  public boolean isExpired(Instant now) {
    return status == ProposalStatus.PENDING && now.isAfter(expiresAt);
  }
}
