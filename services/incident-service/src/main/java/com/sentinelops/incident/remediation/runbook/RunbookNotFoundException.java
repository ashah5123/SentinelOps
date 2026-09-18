package com.sentinelops.incident.remediation.runbook;

public class RunbookNotFoundException extends RuntimeException {

  public RunbookNotFoundException(String slug) {
    super("No active runbook found for slug: " + slug);
  }
}
