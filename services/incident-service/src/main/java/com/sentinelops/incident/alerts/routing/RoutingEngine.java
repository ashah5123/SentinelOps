package com.sentinelops.incident.alerts.routing;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Deterministic, versioned routing (section 10). Rules are evaluated in configured list order —
 * first full match wins; every field a rule specifies (non-null) must match exactly, and a field
 * left unset in the rule matches anything. Falls back to {@code sentinelops.alerts.routing.
 * default-route} when no rule matches, so every alert always gets a route. Alert payloads never
 * select a channel, URL, or team directly — only the rule ID they matched decides that, and rules
 * are operator-configured, not payload-controlled.
 */
@Component
public class RoutingEngine {

  private final AlertsProperties.Routing routing;
  private final BusinessHoursEvaluator businessHoursEvaluator;

  public RoutingEngine(AlertsProperties properties, BusinessHoursEvaluator businessHoursEvaluator) {
    this.routing = properties.routing();
    this.businessHoursEvaluator = businessHoursEvaluator;
  }

  public RoutingDecision route(CanonicalAlert alert, String severity) {
    Instant now = Instant.now();
    boolean isBusinessHours = businessHoursEvaluator.isBusinessHours(now);
    String teamLabel = alert.labels() == null ? null : alert.labels().get("team");

    for (AlertsProperties.RoutingRuleConfig rule : routing.rules()) {
      if (matches(rule.severity(), severity)
          && matches(rule.service(), alert.service())
          && matches(rule.environment(), alert.environment())
          && matches(rule.source(), alert.source())
          && matches(rule.teamLabel(), teamLabel)
          && (rule.businessHoursOnly() == null || rule.businessHoursOnly() == isBusinessHours)) {
        return toDecision(rule.id(), rule.version(), rule.route());
      }
    }

    return toDecision("default", 1, routing.defaultRoute());
  }

  private boolean matches(String ruleValue, String actualValue) {
    return ruleValue == null || ruleValue.equals(actualValue);
  }

  private RoutingDecision toDecision(
      String ruleId, int ruleVersion, AlertsProperties.RouteConfig route) {
    return new RoutingDecision(
        ruleId,
        ruleVersion,
        route.team(),
        route.channels(),
        route.escalationDelay(),
        route.queueAiTriage(),
        route.suppress());
  }
}
