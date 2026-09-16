package com.sentinelops.incident.alerts.notification;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.routing.RoutingDecision;
import com.sentinelops.incident.domain.Incident;
import org.springframework.stereotype.Component;

/**
 * Builds the safe, bounded {@link NotificationPayload} a channel actually sends — see the class
 * javadoc there for what is deliberately excluded.
 */
@Component
public class NotificationRenderer {

  private final AlertsProperties.Notification properties;

  public NotificationRenderer(AlertsProperties properties) {
    this.properties = properties.notification();
  }

  public NotificationPayload render(
      Incident incident, String environment, RoutingDecision decision) {
    return render(
        incident, environment, decision.ruleId(), decision.ruleVersion(), decision.team());
  }

  public NotificationPayload render(
      Incident incident, String environment, String ruleId, int ruleVersion, String team) {
    String link =
        properties.incidentLinkBaseUrl() == null || properties.incidentLinkBaseUrl().isBlank()
            ? null
            : properties.incidentLinkBaseUrl().replaceAll("/$", "")
                + "/incidents/"
                + incident.getId();

    String routingReason = "Routed by rule '" + ruleId + "' (v" + ruleVersion + ") to team " + team;

    String acknowledgeInstructions =
        "Acknowledge by transitioning this incident to INVESTIGATING or later in the SentinelOps "
            + "console, or POST /api/v1/incidents/"
            + incident.getId()
            + "/transitions.";

    return new NotificationPayload(
        incident.getId(),
        incident.getIncidentNumber(),
        incident.getSeverity().name(),
        incident.getAffectedService(),
        environment,
        incident.getTitle(),
        link,
        routingReason,
        acknowledgeInstructions);
  }
}
