package com.sentinelops.incident.remediation.execution;

public class InvalidExecutionTransitionException extends RuntimeException {

  public InvalidExecutionTransitionException(
      RemediationExecutionStatus from, RemediationExecutionStatus to) {
    super("cannot transition remediation execution from " + from + " to " + to);
  }
}
