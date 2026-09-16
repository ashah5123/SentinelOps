package com.sentinelops.incident.proposal.web;

import com.sentinelops.incident.proposal.AgentProposalRow;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProposalResponse(
    UUID id,
    UUID incidentId,
    String actionType,
    Map<String, Object> parameters,
    String reason,
    List<String> evidenceReferences,
    long expectedVersion,
    String riskClassification,
    String requestedBy,
    Instant createdAt,
    Instant expiresAt,
    String status,
    String approvedBy,
    Instant approvedAt,
    String reviewNote,
    String rejectedBy,
    Instant rejectedAt,
    String rejectionNote,
    Instant executedAt,
    String executionResult,
    String executionError) {

  public static ProposalResponse from(AgentProposalRow row) {
    return new ProposalResponse(
        row.id(),
        row.incidentId(),
        row.actionType().name(),
        row.parameters(),
        row.reason(),
        row.evidenceReferences(),
        row.expectedVersion(),
        row.riskClassification().name(),
        row.requestedBy(),
        row.createdAt(),
        row.expiresAt(),
        row.status().name(),
        row.approvedBy(),
        row.approvedAt(),
        row.reviewNote(),
        row.rejectedBy(),
        row.rejectedAt(),
        row.rejectionNote(),
        row.executedAt(),
        row.executionResult(),
        row.executionError());
  }
}
