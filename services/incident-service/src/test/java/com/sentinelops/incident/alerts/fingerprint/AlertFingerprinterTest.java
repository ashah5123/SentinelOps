package com.sentinelops.incident.alerts.fingerprint;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.alerts.canonical.AlertStatus;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AlertFingerprinterTest {

  private final AlertFingerprinter fingerprinter = new AlertFingerprinter();

  private CanonicalAlert alertWithLabels(Map<String, String> labels) {
    return new CanonicalAlert(
        "ALERTMANAGER",
        "alertmanager",
        "ext-1",
        AlertStatus.FIRING,
        "HighCpu",
        "summary",
        "description",
        "SEV2",
        "checkout-api",
        "production",
        "us-east-1",
        labels,
        Map.of(),
        Instant.now(),
        null,
        1,
        "hash");
  }

  @Test
  void reorderedLabelsProduceTheSameFingerprint() {
    Map<String, String> labelsA = new LinkedHashMap<>();
    labelsA.put("instance", "host-1");
    labelsA.put("job", "api");

    Map<String, String> labelsB = new LinkedHashMap<>();
    labelsB.put("job", "api");
    labelsB.put("instance", "host-1");

    assertThat(fingerprinter.fingerprint(alertWithLabels(labelsA)))
        .isEqualTo(fingerprinter.fingerprint(alertWithLabels(labelsB)));
  }

  @Test
  void volatileAnnotationsDoNotAffectTheFingerprint() {
    CanonicalAlert a = alertWithLabels(Map.of("instance", "host-1"));
    CanonicalAlert withDifferentAnnotations =
        new CanonicalAlert(
            a.connectorType(),
            a.source(),
            a.externalId(),
            a.status(),
            a.alertName(),
            a.summary(),
            "a completely different description mentioning trace-id=xyz-999",
            a.severity(),
            a.service(),
            a.environment(),
            a.region(),
            a.labels(),
            Map.of("requestId", "req-123"),
            Instant.now().plusSeconds(500),
            a.generatorUrl(),
            a.schemaVersion(),
            a.rawPayloadHash());

    assertThat(fingerprinter.fingerprint(a))
        .isEqualTo(fingerprinter.fingerprint(withDifferentAnnotations));
  }

  @Test
  void nonIdentityLabelsDoNotAffectTheFingerprint() {
    CanonicalAlert a = alertWithLabels(Map.of("instance", "host-1", "trace_id", "abc"));
    CanonicalAlert b = alertWithLabels(Map.of("instance", "host-1", "trace_id", "different-value"));

    assertThat(fingerprinter.fingerprint(a)).isEqualTo(fingerprinter.fingerprint(b));
  }

  @Test
  void differentServicesProduceDistinctFingerprints() {
    CanonicalAlert a = alertWithLabels(Map.of("instance", "host-1"));
    CanonicalAlert differentService =
        new CanonicalAlert(
            a.connectorType(),
            a.source(),
            a.externalId(),
            a.status(),
            a.alertName(),
            a.summary(),
            a.description(),
            a.severity(),
            "orders-api",
            a.environment(),
            a.region(),
            a.labels(),
            a.annotations(),
            a.sourceTimestamp(),
            a.generatorUrl(),
            a.schemaVersion(),
            a.rawPayloadHash());

    assertThat(fingerprinter.fingerprint(a))
        .isNotEqualTo(fingerprinter.fingerprint(differentService));
  }

  @Test
  void differentEnvironmentsProduceDistinctFingerprints() {
    CanonicalAlert a = alertWithLabels(Map.of("instance", "host-1"));
    CanonicalAlert differentEnv =
        new CanonicalAlert(
            a.connectorType(),
            a.source(),
            a.externalId(),
            a.status(),
            a.alertName(),
            a.summary(),
            a.description(),
            a.severity(),
            a.service(),
            "staging",
            a.region(),
            a.labels(),
            a.annotations(),
            a.sourceTimestamp(),
            a.generatorUrl(),
            a.schemaVersion(),
            a.rawPayloadHash());

    assertThat(fingerprinter.fingerprint(a)).isNotEqualTo(fingerprinter.fingerprint(differentEnv));
  }

  @Test
  void differentIdentityLabelValuesProduceDistinctFingerprints() {
    CanonicalAlert a = alertWithLabels(Map.of("instance", "host-1"));
    CanonicalAlert b = alertWithLabels(Map.of("instance", "host-2"));

    assertThat(fingerprinter.fingerprint(a)).isNotEqualTo(fingerprinter.fingerprint(b));
  }

  @Test
  void labelKeyAndValueCasingDoesNotAffectTheFingerprint() {
    CanonicalAlert a = alertWithLabels(Map.of("Instance", "Host-1"));
    CanonicalAlert b = alertWithLabels(Map.of("instance", "host-1"));

    assertThat(fingerprinter.fingerprint(a)).isEqualTo(fingerprinter.fingerprint(b));
  }

  @Test
  void currentVersionIsExplicitlyVersioned() {
    assertThat(AlertFingerprinter.VERSION).isEqualTo(1);
  }

  @Test
  void isDeterministicAcrossRepeatedCalls() {
    CanonicalAlert a = alertWithLabels(Map.of("instance", "host-1"));
    assertThat(fingerprinter.fingerprint(a)).isEqualTo(fingerprinter.fingerprint(a));
  }
}
