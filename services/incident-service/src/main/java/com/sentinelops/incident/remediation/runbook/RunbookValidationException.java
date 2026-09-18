package com.sentinelops.incident.remediation.runbook;

/** Thrown when a runbook YAML document fails structural or semantic validation. */
public class RunbookValidationException extends RuntimeException {

  public RunbookValidationException(String message) {
    super(message);
  }

  public RunbookValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
