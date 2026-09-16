package com.sentinelops.incident.alerts.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.alerts.canonical.AlertStatus;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationEngineTest {

  private final CorrelationEngine engine =
      new CorrelationEngine(
          List.of(
              new ExplicitLabelCorrelationRule(), new ServiceEnvironmentWindowCorrelationRule()));

  private CanonicalAlert alert(String service, String environment, Map<String, String> labels) {
    return new CanonicalAlert(
        "ALERTMANAGER",
        "alertmanager",
        "ext-1",
        AlertStatus.FIRING,
        "HighCpu",
        "summary",
        "description",
        "SEV2",
        service,
        environment,
        null,
        labels,
        Map.of(),
        Instant.now(),
        null,
        1,
        "hash");
  }

  @Test
  void matchesExactlyOneOpenIncidentSharingServiceAndEnvironment() {
    UUID incidentId = UUID.randomUUID();
    List<CorrelationCandidate> candidates =
        List.of(
            new CorrelationCandidate(
                incidentId, "checkout-api", "production", null, Instant.now(), "prometheus"));

    CorrelationDecision decision =
        engine.correlate(alert("checkout-api", "production", Map.of()), candidates);

    assertThat(decision.matched()).isTrue();
    assertThat(decision.incidentId()).isEqualTo(incidentId);
    assertThat(decision.ruleId()).isEqualTo("service-environment-window");
  }

  @Test
  void declinesWhenMultipleCandidatesShareTheSameServiceAndEnvironment() {
    List<CorrelationCandidate> candidates =
        List.of(
            new CorrelationCandidate(
                UUID.randomUUID(), "checkout-api", "production", null, Instant.now(), "prometheus"),
            new CorrelationCandidate(
                UUID.randomUUID(),
                "checkout-api",
                "production",
                null,
                Instant.now(),
                "prometheus"));

    CorrelationDecision decision =
        engine.correlate(alert("checkout-api", "production", Map.of()), candidates);

    assertThat(decision.matched()).isFalse();
    assertThat(decision.ruleId()).isEqualTo("ambiguous");
  }

  @Test
  void noMatchWhenNoCandidateSharesServiceAndEnvironment() {
    List<CorrelationCandidate> candidates =
        List.of(
            new CorrelationCandidate(
                UUID.randomUUID(), "orders-api", "production", null, Instant.now(), "prometheus"));

    CorrelationDecision decision =
        engine.correlate(alert("checkout-api", "production", Map.of()), candidates);

    assertThat(decision.matched()).isFalse();
    assertThat(decision.ruleId()).isNull();
  }

  @Test
  void explicitLabelTakesPriorityOverServiceEnvironmentMatching() {
    UUID explicitTarget = UUID.randomUUID();
    UUID otherCandidate = UUID.randomUUID();
    List<CorrelationCandidate> candidates =
        List.of(
            new CorrelationCandidate(
                explicitTarget, "different-service", "staging", null, Instant.now(), "prometheus"),
            new CorrelationCandidate(
                otherCandidate, "checkout-api", "production", null, Instant.now(), "prometheus"));

    CanonicalAlert alertWithLabel =
        alert(
            "checkout-api",
            "production",
            Map.of(ExplicitLabelCorrelationRule.LABEL_KEY, explicitTarget.toString()));

    CorrelationDecision decision = engine.correlate(alertWithLabel, candidates);

    assertThat(decision.matched()).isTrue();
    assertThat(decision.incidentId()).isEqualTo(explicitTarget);
    assertThat(decision.ruleId()).isEqualTo("explicit-label");
  }

  @Test
  void neverMergesWhenServiceOrEnvironmentIsBlank() {
    List<CorrelationCandidate> candidates =
        List.of(
            new CorrelationCandidate(
                UUID.randomUUID(), null, "production", null, Instant.now(), "prometheus"));

    CorrelationDecision decision =
        engine.correlate(alert(null, "production", Map.of()), candidates);

    assertThat(decision.matched()).isFalse();
  }
}
