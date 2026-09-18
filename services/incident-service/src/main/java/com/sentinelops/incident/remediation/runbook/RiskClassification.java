package com.sentinelops.incident.remediation.runbook;

/**
 * Mirrors {@code com.sentinelops.incident.proposal.RiskClassification} (Phase 13) but is kept
 * separate since runbook risk and proposal risk are independently versioned concepts.
 */
public enum RiskClassification {
  LOW,
  MEDIUM,
  HIGH
}
