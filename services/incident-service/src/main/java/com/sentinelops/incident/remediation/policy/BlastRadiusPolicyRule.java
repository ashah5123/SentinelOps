package com.sentinelops.incident.remediation.policy;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.util.Optional;

public class BlastRadiusPolicyRule implements PolicyRule {

  private final int maxResourceCount;

  public BlastRadiusPolicyRule(RemediationProperties properties) {
    this.maxResourceCount = properties.blastRadius().maxResourceCount();
  }

  @Override
  public Optional<PolicyDecision> evaluate(PolicyRequest request) {
    if (request.blastRadiusResourceCount() > maxResourceCount) {
      return Optional.of(
          new PolicyDecision(
              PolicyOutcome.DENY,
              "blast radius ("
                  + request.blastRadiusResourceCount()
                  + " resources) exceeds the configured limit ("
                  + maxResourceCount
                  + ")",
              0,
              1));
    }
    return Optional.empty();
  }
}
