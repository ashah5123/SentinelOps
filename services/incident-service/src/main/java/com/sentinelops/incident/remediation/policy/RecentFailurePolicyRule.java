package com.sentinelops.incident.remediation.policy;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.util.Optional;

/**
 * Acts as a coarse, policy-level circuit breaker: if the same runbook/environment has failed or
 * rolled back too many times recently, deny further attempts until an operator intervenes,
 * independent of the per-adapter-type circuit breaker used during execution.
 */
public class RecentFailurePolicyRule implements PolicyRule {

  private final int failureThreshold;

  public RecentFailurePolicyRule(RemediationProperties properties) {
    this.failureThreshold = properties.circuitBreaker().failureThreshold();
  }

  @Override
  public Optional<PolicyDecision> evaluate(PolicyRequest request) {
    if (request.recentFailureCount() >= failureThreshold) {
      return Optional.of(
          new PolicyDecision(
              PolicyOutcome.DENY,
              "recent failure count ("
                  + request.recentFailureCount()
                  + ") meets or exceeds the policy threshold ("
                  + failureThreshold
                  + ")",
              0,
              1));
    }
    return Optional.empty();
  }
}
