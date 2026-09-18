package com.sentinelops.incident.remediation.execution;

import java.util.UUID;

public class RemediationNotFoundException extends RuntimeException {

  public RemediationNotFoundException(UUID id) {
    super("Remediation execution not found: " + id);
  }
}
