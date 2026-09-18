package com.sentinelops.incident.remediation.execution;

import com.sentinelops.incident.remediation.adapters.RemediationActionType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record RemediationStepRow(
    UUID id,
    UUID executionId,
    int stepIndex,
    String stepName,
    RemediationActionType adapterType,
    Map<String, Object> parameters,
    boolean rollbackStep,
    RemediationStepStatus status,
    int attemptCount,
    Instant startedAt,
    Instant completedAt,
    Map<String, Object> output,
    String error) {}
