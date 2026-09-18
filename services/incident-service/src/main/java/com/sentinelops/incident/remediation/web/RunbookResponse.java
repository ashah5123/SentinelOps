package com.sentinelops.incident.remediation.web;

import com.sentinelops.incident.remediation.runbook.RemediationRunbookRow;
import java.time.Instant;
import java.util.UUID;

public record RunbookResponse(
    UUID id,
    String slug,
    int version,
    String title,
    String riskClassification,
    String definitionYaml,
    int stepCount,
    boolean active,
    Instant createdAt,
    String createdBy) {

  public static RunbookResponse from(RemediationRunbookRow row) {
    return new RunbookResponse(
        row.id(),
        row.slug(),
        row.version(),
        row.title(),
        row.riskClassification().name(),
        row.definitionYaml(),
        row.stepCount(),
        row.active(),
        row.createdAt(),
        row.createdBy());
  }
}
