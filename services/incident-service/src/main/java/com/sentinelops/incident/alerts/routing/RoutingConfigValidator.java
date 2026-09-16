package com.sentinelops.incident.alerts.routing;

import com.sentinelops.incident.alerts.AlertsProperties;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates {@code sentinelops.alerts.routing} at application startup (section 10) — an invalid or
 * ambiguous routing configuration fails fast rather than silently misrouting alerts at runtime.
 * Deliberately imperative rather than relying on cascading JSR-380 annotations through a config
 * list, so every failure mode below is explicit and directly testable.
 */
@Component
public class RoutingConfigValidator {

  private static final Set<String> VALID_CHANNELS = Set.of("EMAIL", "WEBHOOK", "IN_APP");

  private final AlertsProperties properties;

  public RoutingConfigValidator(AlertsProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  public void validateOnStartup() {
    List<String> violations = validate(properties.routing());
    if (!violations.isEmpty()) {
      throw new IllegalStateException(
          "Invalid sentinelops.alerts.routing configuration: " + String.join("; ", violations));
    }
  }

  public List<String> validate(AlertsProperties.Routing routing) {
    List<String> violations = new ArrayList<>();
    Set<String> seenIds = new HashSet<>();

    for (AlertsProperties.RoutingRuleConfig rule : routing.rules()) {
      if (!seenIds.add(rule.id())) {
        violations.add("duplicate routing rule id: " + rule.id());
      }
      validateRoute(rule.id(), rule.route(), violations);
    }

    validateRoute("default", routing.defaultRoute(), violations);

    AlertsProperties.BusinessHours businessHours = routing.businessHours();
    if (businessHours.startHour() < 0
        || businessHours.startHour() > 23
        || businessHours.endHour() < 0
        || businessHours.endHour() > 23
        || businessHours.startHour() >= businessHours.endHour()) {
      violations.add(
          "business-hours window is invalid: startHour="
              + businessHours.startHour()
              + " endHour="
              + businessHours.endHour());
    }

    return violations;
  }

  private void validateRoute(
      String ruleId, AlertsProperties.RouteConfig route, List<String> violations) {
    if (route == null) {
      violations.add("routing rule '" + ruleId + "' has no route configured");
      return;
    }
    if (route.team() == null || route.team().isBlank()) {
      violations.add("routing rule '" + ruleId + "' has a blank team");
    }
    if (route.channels() == null || route.channels().isEmpty()) {
      violations.add("routing rule '" + ruleId + "' has no notification channels");
    } else {
      for (String channel : route.channels()) {
        if (!VALID_CHANNELS.contains(channel)) {
          violations.add(
              "routing rule '"
                  + ruleId
                  + "' has unknown channel '"
                  + channel
                  + "' (expected one of "
                  + VALID_CHANNELS
                  + ")");
        }
      }
    }
    if (route.escalationDelay() != null && route.escalationDelay().isNegative()) {
      violations.add("routing rule '" + ruleId + "' has a negative escalationDelay");
    }
  }
}
