package com.sentinelops.incident.remediation.policy;

import com.sentinelops.incident.remediation.RemediationProperties;
import com.sentinelops.incident.remediation.runbook.RiskClassification;
import java.util.Optional;
import java.util.Set;

/**
 * The terminal rule: maps risk classification to a base approval requirement, escalated when the
 * target environment is restricted (e.g. production always needs at least one more approver than
 * the base risk tier would otherwise require). Always returns a decision — this is what makes the
 * overall chain deny-by-default-safe: every request either matches an earlier DENY rule or falls
 * through to here, and here every case is explicitly handled.
 */
public class RiskClassificationPolicyRule implements PolicyRule {

  private final Set<String> restrictedEnvironments;

  public RiskClassificationPolicyRule(RemediationProperties properties) {
    this.restrictedEnvironments = Set.copyOf(properties.blastRadius().restrictedEnvironments());
  }

  @Override
  public Optional<PolicyDecision> evaluate(PolicyRequest request) {
    boolean restricted = restrictedEnvironments.contains(request.environment());
    RiskClassification risk = request.riskClassification();

    return switch (risk) {
      case LOW ->
          restricted
              ? Optional.of(
                  new PolicyDecision(
                      PolicyOutcome.REQUIRE_APPROVAL,
                      "low-risk action in restricted environment '"
                          + request.environment()
                          + "' requires one approval",
                      1,
                      1))
              : Optional.of(
                  new PolicyDecision(PolicyOutcome.ALLOW, "low-risk action auto-approved", 0, 1));
      case MEDIUM ->
          Optional.of(
              new PolicyDecision(
                  PolicyOutcome.REQUIRE_APPROVAL,
                  "medium-risk action requires approval"
                      + (restricted ? " (two approvals — restricted environment)" : ""),
                  restricted ? 2 : 1,
                  1));
      case HIGH ->
          Optional.of(
              new PolicyDecision(
                  PolicyOutcome.REQUIRE_APPROVAL,
                  "high-risk action requires two-person approval",
                  2,
                  1));
    };
  }
}
