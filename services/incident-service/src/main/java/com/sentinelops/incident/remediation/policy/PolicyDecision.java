package com.sentinelops.incident.remediation.policy;

/**
 * A deterministic policy decision with a human-readable explanation (section: "Policy as code").
 */
public record PolicyDecision(
    PolicyOutcome outcome, String reason, int requiredApprovals, int policyVersion) {

  public boolean isDenied() {
    return outcome == PolicyOutcome.DENY;
  }
}
