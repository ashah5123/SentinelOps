package com.sentinelops.incident.remediation.execution;

public class RemediationConflictException extends RuntimeException {

  private final String code;

  public RemediationConflictException(String code, String message) {
    super(message);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
