package com.sentinelops.incident.remediation.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.remediation.RemediationProperties;
import com.sentinelops.incident.remediation.runbook.RiskClassification;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PolicyEngineTest {

  private PolicyEngine engine;

  @BeforeEach
  void setUp() {
    RemediationProperties properties =
        new RemediationProperties(
            List.of("triage-search"),
            List.of("notification-dispatch"),
            List.of("ping"),
            5,
            Duration.ofMinutes(5),
            Duration.ofSeconds(5),
            5,
            Duration.ofSeconds(10),
            new RemediationProperties.BlastRadius(3, List.of("production")),
            new RemediationProperties.CircuitBreakerSettings(3, Duration.ofMinutes(1)),
            new RemediationProperties.MaintenanceWindow(
                List.of("SATURDAY", "SUNDAY"), 2, 6, "UTC"));
    engine = new PolicyEngine(properties);
  }

  private PolicyRequest request(
      Set<String> roles,
      String environment,
      RiskClassification risk,
      int blastRadius,
      boolean maintenanceWindow,
      int recentFailures) {
    return new PolicyRequest(
        roles,
        IncidentSeverity.SEV2,
        environment,
        risk,
        blastRadius,
        maintenanceWindow,
        recentFailures);
  }

  @Test
  void deniesByDefaultWhenActorLacksEligibleRole() {
    PolicyDecision decision =
        engine.evaluate(request(Set.of("VIEWER"), "staging", RiskClassification.LOW, 1, false, 0));

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(decision.reason()).contains("RESPONDER or ADMIN");
  }

  @Test
  void allowsLowRiskInUnrestrictedEnvironment() {
    PolicyDecision decision =
        engine.evaluate(
            request(Set.of("RESPONDER"), "staging", RiskClassification.LOW, 1, false, 0));

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.ALLOW);
    assertThat(decision.requiredApprovals()).isZero();
  }

  @Test
  void requiresApprovalForLowRiskInRestrictedEnvironment() {
    PolicyDecision decision =
        engine.evaluate(
            request(Set.of("RESPONDER"), "production", RiskClassification.LOW, 1, false, 0));

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
    assertThat(decision.requiredApprovals()).isEqualTo(1);
  }

  @Test
  void requiresTwoApprovalsForMediumRiskInRestrictedEnvironment() {
    PolicyDecision decision =
        engine.evaluate(
            request(Set.of("RESPONDER"), "production", RiskClassification.MEDIUM, 1, false, 0));

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
    assertThat(decision.requiredApprovals()).isEqualTo(2);
  }

  @Test
  void requiresTwoPersonApprovalForHighRiskInsideMaintenanceWindow() {
    PolicyDecision decision =
        engine.evaluate(
            request(Set.of("RESPONDER"), "production", RiskClassification.HIGH, 1, true, 0));

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.REQUIRE_APPROVAL);
    assertThat(decision.requiredApprovals()).isEqualTo(2);
  }

  @Test
  void deniesHighRiskInRestrictedEnvironmentOutsideMaintenanceWindow() {
    PolicyDecision decision =
        engine.evaluate(
            request(Set.of("ADMIN"), "production", RiskClassification.HIGH, 1, false, 0));

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(decision.reason()).contains("maintenance window");
  }

  @Test
  void deniesWhenBlastRadiusExceedsLimit() {
    PolicyDecision decision =
        engine.evaluate(
            request(Set.of("RESPONDER"), "staging", RiskClassification.LOW, 4, false, 0));

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(decision.reason()).contains("blast radius");
  }

  @Test
  void deniesWhenRecentFailureCountMeetsThreshold() {
    PolicyDecision decision =
        engine.evaluate(
            request(Set.of("RESPONDER"), "staging", RiskClassification.LOW, 1, false, 3));

    assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
    assertThat(decision.reason()).contains("failure count");
  }
}
