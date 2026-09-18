package com.sentinelops.incident.remediation.runbook;

import java.time.Instant;
import java.util.UUID;

public record RemediationRunbookRow(
    UUID id,
    String slug,
    int version,
    String title,
    RiskClassification riskClassification,
    String definitionYaml,
    String definitionHash,
    int stepCount,
    boolean active,
    Instant createdAt,
    String createdBy) {}
