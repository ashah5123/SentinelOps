package com.sentinelops.incident.alerts.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.AlertsPropertiesFixtures;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class RoutingConfigValidatorTest {

  private RoutingConfigValidator validatorFor(AlertsProperties properties) {
    return new RoutingConfigValidator(properties);
  }

  private AlertsProperties.RouteConfig validRoute() {
    return new AlertsProperties.RouteConfig(
        "team", List.of("EMAIL"), Duration.ofMinutes(1), false, false);
  }

  @Test
  void aValidConfigurationHasNoViolations() {
    RoutingConfigValidator validator = validatorFor(AlertsPropertiesFixtures.minimal());
    assertThat(validator.validate(AlertsPropertiesFixtures.minimal().routing())).isEmpty();
  }

  @Test
  void duplicateRuleIdsAreRejected() {
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "dup", 1, null, null, null, null, null, null, validRoute()),
            new AlertsProperties.RoutingRuleConfig(
                "dup", 1, null, null, null, null, null, null, validRoute()));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules, validRoute(), new AlertsProperties.BusinessHours("UTC", 9, 17));

    List<String> violations = validatorFor(AlertsPropertiesFixtures.minimal()).validate(routing);

    assertThat(violations).anyMatch(v -> v.contains("duplicate"));
  }

  @Test
  void anUnknownChannelIsRejected() {
    var route =
        new AlertsProperties.RouteConfig(
            "team", List.of("SMS"), Duration.ofMinutes(1), false, false);
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "r1", 1, null, null, null, null, null, null, route));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules, validRoute(), new AlertsProperties.BusinessHours("UTC", 9, 17));

    List<String> violations = validatorFor(AlertsPropertiesFixtures.minimal()).validate(routing);

    assertThat(violations).anyMatch(v -> v.contains("SMS"));
  }

  @Test
  void aBlankTeamIsRejected() {
    var route =
        new AlertsProperties.RouteConfig("", List.of("EMAIL"), Duration.ofMinutes(1), false, false);
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "r1", 1, null, null, null, null, null, null, route));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules, validRoute(), new AlertsProperties.BusinessHours("UTC", 9, 17));

    assertThat(validatorFor(AlertsPropertiesFixtures.minimal()).validate(routing))
        .anyMatch(v -> v.contains("blank team"));
  }

  @Test
  void aNegativeEscalationDelayIsRejected() {
    var route =
        new AlertsProperties.RouteConfig(
            "team", List.of("EMAIL"), Duration.ofSeconds(-1), false, false);
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "r1", 1, null, null, null, null, null, null, route));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules, validRoute(), new AlertsProperties.BusinessHours("UTC", 9, 17));

    assertThat(validatorFor(AlertsPropertiesFixtures.minimal()).validate(routing))
        .anyMatch(v -> v.contains("negative"));
  }

  @Test
  void anInvalidBusinessHoursWindowIsRejected() {
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "r1", 1, null, null, null, null, null, null, validRoute()));
    AlertsProperties.Routing routing =
        new AlertsProperties.Routing(
            rules, validRoute(), new AlertsProperties.BusinessHours("UTC", 17, 9));

    assertThat(validatorFor(AlertsPropertiesFixtures.minimal()).validate(routing))
        .anyMatch(v -> v.contains("business-hours"));
  }

  @Test
  void constructorThrowsOnStartupWhenConfigurationIsInvalid() {
    var rules =
        List.of(
            new AlertsProperties.RoutingRuleConfig(
                "dup", 1, null, null, null, null, null, null, validRoute()),
            new AlertsProperties.RoutingRuleConfig(
                "dup", 1, null, null, null, null, null, null, validRoute()));
    AlertsProperties.Routing invalidRouting =
        new AlertsProperties.Routing(
            rules, validRoute(), new AlertsProperties.BusinessHours("UTC", 9, 17));
    AlertsProperties base = AlertsPropertiesFixtures.minimal();
    AlertsProperties broken =
        new AlertsProperties(
            base.ingestion(),
            base.hmac(),
            base.alertmanager(),
            base.rateLimit(),
            base.correlation(),
            base.notification(),
            base.escalation(),
            invalidRouting);

    RoutingConfigValidator validator = new RoutingConfigValidator(broken);
    assertThatThrownBy(validator::validateOnStartup).isInstanceOf(IllegalStateException.class);
  }
}
