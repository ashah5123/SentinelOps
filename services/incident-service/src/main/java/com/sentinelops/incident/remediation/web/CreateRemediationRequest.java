package com.sentinelops.incident.remediation.web;

import jakarta.validation.constraints.NotBlank;

public record CreateRemediationRequest(
    @NotBlank String runbookSlug,
    String incidentId,
    String proposalId,
    boolean dryRun,
    @NotBlank String idempotencyKey) {}
