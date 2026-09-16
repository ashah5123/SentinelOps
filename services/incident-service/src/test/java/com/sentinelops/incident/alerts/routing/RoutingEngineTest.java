package com.sentinelops.incident.alerts.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.canonical.AlertStatus;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutingEngineTest {

  private CanonicalAlert alert(
      String service, String environment, String source, Map<String, String> labels) {
    return new CanonicalAlert(
        "ALERTMANAGER",
        source,
        "ext-1",
        AlertStatus.FIRING,
        "HighCpu",
        "summary",
        "description",
        "SEV1",
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

  private AlertsProperties.RouteConfig route(String team, List<String> channels) {
    return new AlertsProperties.RouteConfig(team, channels, Duration.ofMinutes(5), true, false);
  }

  @Test
  void firstMatchingRuleWinsInListOrder() {
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "sev1-checkout",
                1,
                "SEV1",
                "checkout-api",
                null,
                null,
                null,
                null,
                route("checkout-team", List.of("EMAIL"))),
            new AlertsProperties.RoutingRuleConfig(
                "sev1-any",
                1,
                "SEV1",
                null,
                null,
                null,
                null,
                null,
                route("platform-oncall", List.of("WEBHOOK"))));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules,
            route("unassigned", List.of("IN_APP")),
            new AlertsProperties.BusinessHours("UTC", 9, 17));
    RoutingEngine engine = new RoutingEngine(propsWith(routing), alwaysBusinessHours());

    RoutingDecision decision =
        engine.route(alert("checkout-api", "production", "prometheus", Map.of()), "SEV1");

    assertThat(decision.ruleId()).isEqualTo("sev1-checkout");
    assertThat(decision.team()).isEqualTo("checkout-team");
  }

  @Test
  void fallsBackToDefaultRouteWhenNoRuleMatches() {
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "sev1-checkout",
                1,
                "SEV1",
                "checkout-api",
                null,
                null,
                null,
                null,
                route("checkout-team", List.of("EMAIL"))));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules,
            route("unassigned", List.of("IN_APP")),
            new AlertsProperties.BusinessHours("UTC", 9, 17));
    RoutingEngine engine = new RoutingEngine(propsWith(routing), alwaysBusinessHours());

    RoutingDecision decision =
        engine.route(alert("orders-api", "production", "prometheus", Map.of()), "SEV3");

    assertThat(decision.ruleId()).isEqualTo("default");
    assertThat(decision.team()).isEqualTo("unassigned");
  }

  @Test
  void teamLabelMatchIsAgainstTheAlertsOwnTeamLabel() {
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "team-payments",
                1,
                null,
                null,
                null,
                null,
                "payments",
                null,
                route("payments-team", List.of("EMAIL"))));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules,
            route("unassigned", List.of("IN_APP")),
            new AlertsProperties.BusinessHours("UTC", 9, 17));
    RoutingEngine engine = new RoutingEngine(propsWith(routing), alwaysBusinessHours());

    RoutingDecision matching =
        engine.route(alert("svc", "prod", "src", Map.of("team", "payments")), "SEV2");
    RoutingDecision nonMatching =
        engine.route(alert("svc", "prod", "src", Map.of("team", "other")), "SEV2");

    assertThat(matching.ruleId()).isEqualTo("team-payments");
    assertThat(nonMatching.ruleId()).isEqualTo("default");
  }

  @Test
  void businessHoursOnlyRuleOnlyMatchesDuringBusinessHours() {
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "business-hours-rule",
                1,
                null,
                null,
                null,
                null,
                null,
                true,
                route("day-team", List.of("EMAIL"))));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules,
            route("unassigned", List.of("IN_APP")),
            new AlertsProperties.BusinessHours("UTC", 9, 17));

    RoutingEngine duringHours = new RoutingEngine(propsWith(routing), alwaysBusinessHours());
    RoutingEngine outsideHours = new RoutingEngine(propsWith(routing), neverBusinessHours());

    assertThat(duringHours.route(alert("svc", "prod", "src", Map.of()), "SEV2").ruleId())
        .isEqualTo("business-hours-rule");
    assertThat(outsideHours.route(alert("svc", "prod", "src", Map.of()), "SEV2").ruleId())
        .isEqualTo("default");
  }

  private BusinessHoursEvaluator alwaysBusinessHours() {
    BusinessHoursEvaluator mock = mock(BusinessHoursEvaluator.class);
    when(mock.isBusinessHours(org.mockito.ArgumentMatchers.any())).thenReturn(true);
    return mock;
  }

  private BusinessHoursEvaluator neverBusinessHours() {
    BusinessHoursEvaluator mock = mock(BusinessHoursEvaluator.class);
    when(mock.isBusinessHours(org.mockito.ArgumentMatchers.any())).thenReturn(false);
    return mock;
  }

  private AlertsProperties propsWith(AlertsProperties.Routing routing) {
    var base = com.sentinelops.incident.alerts.AlertsPropertiesFixtures.minimal();
    return new AlertsProperties(
        base.ingestion(),
        base.hmac(),
        base.alertmanager(),
        base.rateLimit(),
        base.correlation(),
        base.notification(),
        base.escalation(),
        routing);
  }
}
