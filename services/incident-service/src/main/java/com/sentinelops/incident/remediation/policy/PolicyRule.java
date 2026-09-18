package com.sentinelops.incident.remediation.policy;

import java.util.Optional;

/** One rule in the ordered policy chain. Returns empty to defer to the next rule. */
public interface PolicyRule {

  Optional<PolicyDecision> evaluate(PolicyRequest request);
}
