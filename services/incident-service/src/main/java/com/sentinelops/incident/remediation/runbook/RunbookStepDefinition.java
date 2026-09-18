package com.sentinelops.incident.remediation.runbook;

import com.sentinelops.incident.remediation.adapters.RemediationActionType;
import java.util.Map;

/**
 * One step of a runbook (forward or rollback). {@code maxRetries} attempts use {@code
 * backoffInitialMillis}/{@code backoffMultiplier} exponential backoff, mirroring {@code
 * JitteredExponentialBackOff} elsewhere in the codebase.
 */
public record RunbookStepDefinition(
    String name,
    RemediationActionType adapterType,
    Map<String, Object> parameters,
    int timeoutSeconds,
    int maxRetries,
    long backoffInitialMillis,
    double backoffMultiplier) {

  public RunbookStepDefinition {
    if (name == null || name.isBlank()) {
      throw new RunbookValidationException("step name is required");
    }
    if (adapterType == null) {
      throw new RunbookValidationException(
          "step '" + name + "' has an unknown or missing adapter type");
    }
    if (parameters == null) {
      parameters = Map.of();
    }
    if (timeoutSeconds < 1 || timeoutSeconds > 300) {
      throw new RunbookValidationException(
          "step '" + name + "' timeoutSeconds must be between 1 and 300");
    }
    if (maxRetries < 0 || maxRetries > 5) {
      throw new RunbookValidationException(
          "step '" + name + "' maxRetries must be between 0 and 5");
    }
    if (backoffInitialMillis < 100 || backoffInitialMillis > 60_000) {
      throw new RunbookValidationException(
          "step '" + name + "' backoffInitialMillis must be between 100 and 60000");
    }
    if (backoffMultiplier < 1.0 || backoffMultiplier > 10.0) {
      throw new RunbookValidationException(
          "step '" + name + "' backoffMultiplier must be between 1.0 and 10.0");
    }
  }
}
