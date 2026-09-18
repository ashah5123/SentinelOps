package com.sentinelops.incident.remediation.policy;

import java.util.Optional;
import java.util.Set;

/** Only RESPONDER/ADMIN actors may trigger remediation at all — a VIEWER is always denied. */
public class AuthorizationPolicyRule implements PolicyRule {

  private static final Set<String> ELIGIBLE_ROLES = Set.of("RESPONDER", "ADMIN");

  @Override
  public Optional<PolicyDecision> evaluate(PolicyRequest request) {
    boolean eligible = request.actorRoles().stream().anyMatch(ELIGIBLE_ROLES::contains);
    if (!eligible) {
      return Optional.of(
          new PolicyDecision(PolicyOutcome.DENY, "actor lacks RESPONDER or ADMIN role", 0, 1));
    }
    return Optional.empty();
  }
}
