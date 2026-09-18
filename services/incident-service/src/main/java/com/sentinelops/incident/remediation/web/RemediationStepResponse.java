package com.sentinelops.incident.remediation.web;

import com.sentinelops.incident.remediation.execution.RemediationStepRow;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemediationStepResponse(
    UUID id,
    int stepIndex,
    String stepName,
    String adapterType,
    boolean rollbackStep,
    String status,
    int attemptCount,
    Instant startedAt,
    Instant completedAt,
    Map<String, Object> output,
    String error) {

  public static RemediationStepResponse from(RemediationStepRow row) {
    return new RemediationStepResponse(
        row.id(),
        row.stepIndex(),
        row.stepName(),
        row.adapterType().name(),
        row.rollbackStep(),
        row.status().name(),
        row.attemptCount(),
        row.startedAt(),
        row.completedAt(),
        row.output(),
        row.error());
  }
}
