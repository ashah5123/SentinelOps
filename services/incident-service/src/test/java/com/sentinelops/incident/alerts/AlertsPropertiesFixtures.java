package com.sentinelops.incident.alerts;

import java.time.Duration;
import java.util.List;

/**
 * Shared {@link AlertsProperties} test fixtures — avoids duplicating this large constructor across
 * every Phase 12 test class.
 */
public final class AlertsPropertiesFixtures {

  private AlertsPropertiesFixtures() {}

  public static AlertsProperties.Ingestion defaultIngestion() {
    return new AlertsProperties.Ingestion(
        50,
        50,
        100,
        500,
        200,
        500,
        2000,
        1_000_000,
        Duration.ofMinutes(5),
        Duration.ofDays(30),
        List.of(1));
  }

  public static AlertsProperties.Hmac hmac(
      String currentSecretId,
      String currentSecret,
      String previousSecretId,
      String previousSecret,
      Duration replayWindow) {
    return new AlertsProperties.Hmac(
        currentSecretId, currentSecret, previousSecretId, previousSecret, replayWindow);
  }

  public static AlertsProperties minimal() {
    return new AlertsProperties(
        defaultIngestion(),
        hmac("current", "test-secret", null, null, Duration.ofMinutes(5)),
        new AlertsProperties.Alertmanager("test-token"),
        new AlertsProperties.RateLimit(100, Duration.ofMinutes(1)),
        new AlertsProperties.Correlation(Duration.ofMinutes(30), 50),
        new AlertsProperties.Notification(
            "http://localhost:5173",
            "",
            "",
            "",
            3,
            Duration.ofSeconds(1),
            Duration.ofSeconds(10),
            0.2,
            Duration.ofSeconds(1),
            10),
        new AlertsProperties.Escalation(Duration.ofSeconds(1), 10),
        new AlertsProperties.Routing(
            List.of(
                new AlertsProperties.RoutingRuleConfig(
                    "sev1",
                    1,
                    "SEV1",
                    null,
                    null,
                    null,
                    null,
                    null,
                    new AlertsProperties.RouteConfig(
                        "platform-oncall",
                        List.of("EMAIL", "WEBHOOK"),
                        Duration.ofMinutes(5),
                        true,
                        false))),
            new AlertsProperties.RouteConfig(
                "unassigned", List.of("IN_APP"), Duration.ZERO, false, false),
            new AlertsProperties.BusinessHours("UTC", 9, 17)));
  }
}
