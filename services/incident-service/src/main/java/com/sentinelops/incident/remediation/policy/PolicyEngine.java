package com.sentinelops.incident.remediation.policy;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Deterministic, ordered policy evaluation (section: "Policy as code"): the first rule to return a
 * decision wins. {@link RiskClassificationPolicyRule} is always last and always returns a decision,
 * so a request can never fall through with no decision — this is the deny-by-default guarantee
 * (verified by {@code PolicyEngineTest#deniesByDefaultWhenNoRuleExplicitlyAllows} via an
 * authorization failure, the earliest possible deny).
 */
@Component
public class PolicyEngine {

  private final List<PolicyRule> rules;

  public PolicyEngine(RemediationProperties properties) {
    this.rules =
        List.of(
            new AuthorizationPolicyRule(),
            new RecentFailurePolicyRule(properties),
            new BlastRadiusPolicyRule(properties),
            new MaintenanceWindowPolicyRule(properties),
            new RiskClassificationPolicyRule(properties));
  }

  public PolicyDecision evaluate(PolicyRequest request) {
    for (PolicyRule rule : rules) {
      var decision = rule.evaluate(request);
      if (decision.isPresent()) {
        return decision.get();
      }
    }
    return new PolicyDecision(PolicyOutcome.DENY, "no policy rule reached a decision", 0, 1);
  }
}
