package com.sentinelops.incident.remediation.policy;

import com.sentinelops.incident.remediation.RemediationProperties;
import com.sentinelops.incident.remediation.runbook.RiskClassification;
import java.util.Optional;
import java.util.Set;

/**
 * A HIGH-risk action targeting a restricted environment (e.g. production) may only run inside an
 * approved maintenance window — outside one it is denied outright, regardless of approvals.
 */
public class MaintenanceWindowPolicyRule implements PolicyRule {

  private final Set<String> restrictedEnvironments;

  public MaintenanceWindowPolicyRule(RemediationProperties properties) {
    this.restrictedEnvironments = Set.copyOf(properties.blastRadius().restrictedEnvironments());
  }

  @Override
  public Optional<PolicyDecision> evaluate(PolicyRequest request) {
    boolean restricted = restrictedEnvironments.contains(request.environment());
    boolean highRisk = request.riskClassification() == RiskClassification.HIGH;
    if (restricted && highRisk && !request.maintenanceWindowActive()) {
      return Optional.of(
          new PolicyDecision(
              PolicyOutcome.DENY,
              "high-risk actions in restricted environment '"
                  + request.environment()
                  + "' require an active maintenance window",
              0,
              1));
    }
    return Optional.empty();
  }
}
