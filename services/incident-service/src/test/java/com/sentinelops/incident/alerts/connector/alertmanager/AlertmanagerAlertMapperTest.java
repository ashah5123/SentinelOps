package com.sentinelops.incident.alerts.connector.alertmanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.alerts.canonical.AlertStatus;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertmanagerAlertMapperTest {

  private final AlertmanagerAlertMapper mapper = new AlertmanagerAlertMapper();

  @Test
  void mapsAFiringAlertWithSeverityAndServiceLabels() {
    AlertmanagerAlert alert =
        new AlertmanagerAlert(
            "firing",
            Map.of(
                "alertname",
                "HighCpu",
                "severity",
                "critical",
                "service",
                "checkout-api",
                "environment",
                "production"),
            Map.of("summary", "CPU is high", "description", "CPU usage exceeded threshold"),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("0001-01-01T00:00:00Z"),
            "http://prometheus/graph",
            "abc123");

    CanonicalAlert canonical = mapper.map(alert, "hash");

    assertThat(canonical.status()).isEqualTo(AlertStatus.FIRING);
    assertThat(canonical.alertName()).isEqualTo("HighCpu");
    assertThat(canonical.severity()).isEqualTo("SEV1");
    assertThat(canonical.service()).isEqualTo("checkout-api");
    assertThat(canonical.environment()).isEqualTo("production");
    assertThat(canonical.externalId()).isEqualTo("abc123");
    assertThat(canonical.sourceTimestamp()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
    assertThat(canonical.source()).isEqualTo("alertmanager");
  }

  @Test
  void mapsAResolvedAlertUsingEndsAtAsTheSourceTimestamp() {
    AlertmanagerAlert alert =
        new AlertmanagerAlert(
            "resolved",
            Map.of("alertname", "HighCpu"),
            Map.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:10:00Z"),
            null,
            "abc123");

    CanonicalAlert canonical = mapper.map(alert, "hash");

    assertThat(canonical.status()).isEqualTo(AlertStatus.RESOLVED);
    assertThat(canonical.sourceTimestamp()).isEqualTo(Instant.parse("2026-01-01T00:10:00Z"));
  }

  @Test
  void fallsBackToJobLabelWhenServiceLabelIsAbsent() {
    AlertmanagerAlert alert =
        new AlertmanagerAlert(
            "firing",
            Map.of("alertname", "X", "job", "worker-job"),
            Map.of(),
            Instant.now(),
            null,
            null,
            "fp1");

    CanonicalAlert canonical = mapper.map(alert, "hash");

    assertThat(canonical.service()).isEqualTo("worker-job");
  }

  @Test
  void anUnrecognizedSeverityLabelMapsToNull() {
    AlertmanagerAlert alert =
        new AlertmanagerAlert(
            "firing",
            Map.of("alertname", "X", "severity", "unheard-of"),
            Map.of(),
            Instant.now(),
            null,
            null,
            "fp1");

    CanonicalAlert canonical = mapper.map(alert, "hash");

    assertThat(canonical.severity()).isNull();
  }

  @Test
  void unsetEndsAtOnAFiringAlertIsNotTreatedAsSet() {
    // The Alertmanager sentinel "unset" value must never leak through as a real timestamp.
    AlertmanagerAlert alert =
        new AlertmanagerAlert(
            "firing",
            Map.of("alertname", "X"),
            Map.of(),
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("0001-01-01T00:00:00Z"),
            null,
            "fp1");

    CanonicalAlert canonical = mapper.map(alert, "hash");

    assertThat(canonical.sourceTimestamp()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
  }
}
