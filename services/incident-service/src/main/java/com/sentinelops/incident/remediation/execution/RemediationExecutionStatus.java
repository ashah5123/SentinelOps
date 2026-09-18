package com.sentinelops.incident.remediation.execution;

public enum RemediationExecutionStatus {
  PROPOSED,
  APPROVED,
  SCHEDULED,
  RUNNING,
  SUCCEEDED,
  FAILED,
  ROLLED_BACK,
  CANCELLED,
  DENIED
}
