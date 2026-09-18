package com.sentinelops.incident.remediation.execution;

import java.util.Set;
import java.util.UUID;

public record RemediationProposeRequest(
    String runbookSlug,
    UUID incidentId,
    UUID proposalId,
    boolean dryRun,
    String requestedBy,
    Set<String> requestedByRoles,
    String correlationId,
    String idempotencyKey) {}
