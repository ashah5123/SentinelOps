package com.sentinelops.incident.alerts.canonical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.AlertsPropertiesFixtures;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CanonicalAlertValidatorTest {

  private CanonicalAlertValidator validator;

  @BeforeEach
  void setUp() {
    AlertsProperties.Ingestion limits =
        new AlertsProperties.Ingestion(
            5,
            5,
            20,
            50,
            200,
            500,
            2000,
            1_000_000,
            Duration.ofMinutes(5),
            Duration.ofDays(30),
            List.of(1));
    AlertsProperties properties = withIngestion(limits);
    validator = new CanonicalAlertValidator(properties);
  }

  private CanonicalAlert validAlert() {
    return new CanonicalAlert(
        "GENERIC_WEBHOOK",
        "custom-monitor",
        "ext-1",
        AlertStatus.FIRING,
        "HighCpu",
        "summary",
        "description",
        "SEV2",
        "checkout-api",
        "production",
        "us-east-1",
        Map.of("instance", "host-1"),
        Map.of(),
        Instant.now(),
        "https://example.com/graph",
        1,
        "hash");
  }

  @Test
  void aValidAlertPassesValidation() {
    validator.validate(validAlert());
  }

  @Test
  void missingSourceIsRejected() {
    CanonicalAlert alert = withSource(validAlert(), null);
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void missingAlertNameIsRejected() {
    CanonicalAlert a = validAlert();
    CanonicalAlert alert =
        new CanonicalAlert(
            a.connectorType(),
            a.source(),
            a.externalId(),
            a.status(),
            null,
            a.summary(),
            a.description(),
            a.severity(),
            a.service(),
            a.environment(),
            a.region(),
            a.labels(),
            a.annotations(),
            a.sourceTimestamp(),
            a.generatorUrl(),
            a.schemaVersion(),
            a.rawPayloadHash());
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void unsupportedSchemaVersionIsRejected() {
    CanonicalAlert a = validAlert();
    CanonicalAlert alert = withSchemaVersion(a, 99);
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void invalidSeverityIsRejected() {
    CanonicalAlert a = validAlert();
    CanonicalAlert alert = withSeverity(a, "SUPER_CRITICAL");
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void sourceTimestampTooFarInTheFutureIsRejected() {
    CanonicalAlert a = validAlert();
    CanonicalAlert alert = withTimestamp(a, Instant.now().plus(Duration.ofHours(1)));
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void sourceTimestampTooFarInThePastIsRejected() {
    CanonicalAlert a = validAlert();
    CanonicalAlert alert = withTimestamp(a, Instant.now().minus(Duration.ofDays(365)));
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void tooManyLabelsIsRejected() {
    Map<String, String> labels = new HashMap<>();
    for (int i = 0; i < 10; i++) {
      labels.put("key" + i, "value" + i);
    }
    CanonicalAlert alert = withLabels(validAlert(), labels);
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void anOverlongLabelValueIsRejected() {
    Map<String, String> labels = Map.of("instance", "x".repeat(1000));
    CanonicalAlert alert = withLabels(validAlert(), labels);
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void aNonHttpGeneratorUrlSchemeIsRejected() {
    CanonicalAlert a = validAlert();
    CanonicalAlert alert = withGeneratorUrl(a, "javascript:alert(1)");
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class);
  }

  @Test
  void aBlankGeneratorUrlIsAllowed() {
    CanonicalAlert alert = withGeneratorUrl(validAlert(), null);
    validator.validate(alert);
  }

  @Test
  void collectsMultipleViolationsInOneException() {
    CanonicalAlert alert = withSource(withSeverity(validAlert(), "BAD"), null);
    assertThatThrownBy(() -> validator.validate(alert))
        .isInstanceOf(AlertValidationException.class)
        .satisfies(
            e ->
                assertThat(((AlertValidationException) e).violations().size())
                    .isGreaterThanOrEqualTo(2));
  }

  private CanonicalAlert withSource(CanonicalAlert a, String source) {
    return new CanonicalAlert(
        a.connectorType(),
        source,
        a.externalId(),
        a.status(),
        a.alertName(),
        a.summary(),
        a.description(),
        a.severity(),
        a.service(),
        a.environment(),
        a.region(),
        a.labels(),
        a.annotations(),
        a.sourceTimestamp(),
        a.generatorUrl(),
        a.schemaVersion(),
        a.rawPayloadHash());
  }

  private CanonicalAlert withSchemaVersion(CanonicalAlert a, int version) {
    return new CanonicalAlert(
        a.connectorType(),
        a.source(),
        a.externalId(),
        a.status(),
        a.alertName(),
        a.summary(),
        a.description(),
        a.severity(),
        a.service(),
        a.environment(),
        a.region(),
        a.labels(),
        a.annotations(),
        a.sourceTimestamp(),
        a.generatorUrl(),
        version,
        a.rawPayloadHash());
  }

  private CanonicalAlert withSeverity(CanonicalAlert a, String severity) {
    return new CanonicalAlert(
        a.connectorType(),
        a.source(),
        a.externalId(),
        a.status(),
        a.alertName(),
        a.summary(),
        a.description(),
        severity,
        a.service(),
        a.environment(),
        a.region(),
        a.labels(),
        a.annotations(),
        a.sourceTimestamp(),
        a.generatorUrl(),
        a.schemaVersion(),
        a.rawPayloadHash());
  }

  private CanonicalAlert withTimestamp(CanonicalAlert a, Instant ts) {
    return new CanonicalAlert(
        a.connectorType(),
        a.source(),
        a.externalId(),
        a.status(),
        a.alertName(),
        a.summary(),
        a.description(),
        a.severity(),
        a.service(),
        a.environment(),
        a.region(),
        a.labels(),
        a.annotations(),
        ts,
        a.generatorUrl(),
        a.schemaVersion(),
        a.rawPayloadHash());
  }

  private CanonicalAlert withLabels(CanonicalAlert a, Map<String, String> labels) {
    return new CanonicalAlert(
        a.connectorType(),
        a.source(),
        a.externalId(),
        a.status(),
        a.alertName(),
        a.summary(),
        a.description(),
        a.severity(),
        a.service(),
        a.environment(),
        a.region(),
        labels,
        a.annotations(),
        a.sourceTimestamp(),
        a.generatorUrl(),
        a.schemaVersion(),
        a.rawPayloadHash());
  }

  private CanonicalAlert withGeneratorUrl(CanonicalAlert a, String url) {
    return new CanonicalAlert(
        a.connectorType(),
        a.source(),
        a.externalId(),
        a.status(),
        a.alertName(),
        a.summary(),
        a.description(),
        a.severity(),
        a.service(),
        a.environment(),
        a.region(),
        a.labels(),
        a.annotations(),
        a.sourceTimestamp(),
        url,
        a.schemaVersion(),
        a.rawPayloadHash());
  }

  private AlertsProperties withIngestion(AlertsProperties.Ingestion ingestion) {
    AlertsProperties base = AlertsPropertiesFixtures.minimal();
    return new AlertsProperties(
        ingestion,
        base.hmac(),
        base.alertmanager(),
        base.rateLimit(),
        base.correlation(),
        base.notification(),
        base.escalation(),
        base.routing());
  }
}
