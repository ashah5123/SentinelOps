package com.sentinelops.incident.alerts.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.routing.RoutingDecision;
import com.sentinelops.incident.domain.Incident;
import com.sentinelops.incident.domain.IncidentSeverity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationRendererTest {

  private Incident sampleIncident() {
    return Incident.detect(
        UUID.randomUUID(),
        "INC-0001",
        "Elevated latency",
        "desc",
        IncidentSeverity.SEV1,
        "prometheus",
        "checkout-api",
        Instant.now(),
        "corr-1",
        null);
  }

  private AlertsProperties propsWithLinkBase(String base) {
    var fixture = com.sentinelops.incident.alerts.AlertsPropertiesFixtures.minimal();
    var notification =
        new AlertsProperties.Notification(
            base,
            "",
            "",
            "",
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            0.2,
            Duration.ofSeconds(1),
            10);
    return new AlertsProperties(
        fixture.ingestion(),
        fixture.hmac(),
        fixture.alertmanager(),
        fixture.rateLimit(),
        fixture.correlation(),
        notification,
        fixture.escalation(),
        fixture.routing());
  }

  @Test
  void rendersOnlyTheSafeFieldsWithABuiltIncidentLink() {
    NotificationRenderer renderer =
        new NotificationRenderer(propsWithLinkBase("http://localhost:5173"));
    Incident incident = sampleIncident();
    RoutingDecision decision =
        new RoutingDecision(
            "sev1-immediate",
            1,
            "platform-oncall",
            List.of("EMAIL"),
            Duration.ofMinutes(5),
            true,
            false);

    NotificationPayload payload = renderer.render(incident, "production", decision);

    assertThat(payload.incidentId()).isEqualTo(incident.getId());
    assertThat(payload.incidentNumber()).isEqualTo("INC-0001");
    assertThat(payload.severity()).isEqualTo("SEV1");
    assertThat(payload.service()).isEqualTo("checkout-api");
    assertThat(payload.environment()).isEqualTo("production");
    assertThat(payload.summary()).isEqualTo("Elevated latency");
    assertThat(payload.incidentLink())
        .isEqualTo("http://localhost:5173/incidents/" + incident.getId());
    assertThat(payload.routingReason()).contains("sev1-immediate").contains("platform-oncall");
    assertThat(payload.acknowledgeInstructions()).isNotBlank();
  }

  @Test
  void noLinkIsBuiltWhenTheBaseUrlIsBlank() {
    NotificationRenderer renderer = new NotificationRenderer(propsWithLinkBase(""));
    RoutingDecision decision =
        new RoutingDecision(
            "default", 1, "unassigned", List.of("IN_APP"), Duration.ZERO, false, false);

    NotificationPayload payload = renderer.render(sampleIncident(), null, decision);

    assertThat(payload.incidentLink()).isNull();
  }
}
