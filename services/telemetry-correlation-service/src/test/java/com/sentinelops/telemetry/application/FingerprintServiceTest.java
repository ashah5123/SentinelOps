package com.sentinelops.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FingerprintServiceTest {

  private final FingerprintService fingerprints = new FingerprintService();

  @Test
  void metricFingerprintIsDeterministicForTheSameInputs() {
    Instant observedAt = Instant.parse("2026-01-01T00:00:00Z");
    Map<String, String> labels = Map.of("job", "incident-service");

    String first =
        fingerprints.metricFingerprint("incident-service", "request_rate", observedAt, labels);
    String second =
        fingerprints.metricFingerprint("incident-service", "request_rate", observedAt, labels);

    assertThat(first).isEqualTo(second);
  }

  @Test
  void metricFingerprintDiffersWhenValueContextDiffers() {
    Instant observedAt = Instant.parse("2026-01-01T00:00:00Z");

    String a =
        fingerprints.metricFingerprint("incident-service", "request_rate", observedAt, Map.of());
    String b =
        fingerprints.metricFingerprint("incident-service", "error_rate", observedAt, Map.of());

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void metricFingerprintIsInsensitiveToLabelOrdering() {
    Instant observedAt = Instant.parse("2026-01-01T00:00:00Z");
    Map<String, String> labelsA = new java.util.LinkedHashMap<>();
    labelsA.put("job", "svc");
    labelsA.put("method", "GET");
    Map<String, String> labelsB = new java.util.LinkedHashMap<>();
    labelsB.put("method", "GET");
    labelsB.put("job", "svc");

    String a = fingerprints.metricFingerprint("svc", "request_rate", observedAt, labelsA);
    String b = fingerprints.metricFingerprint("svc", "request_rate", observedAt, labelsB);

    assertThat(a).isEqualTo(b);
  }

  @Test
  void logFingerprintDiffersForDifferentSummaries() {
    Instant observedAt = Instant.parse("2026-01-01T00:00:00Z");

    String a = fingerprints.logFingerprint("incident-service", observedAt, "first message");
    String b = fingerprints.logFingerprint("incident-service", observedAt, "second message");

    assertThat(a).isNotEqualTo(b);
  }

  @Test
  void traceFingerprintDistinguishesSpansWithinTheSameTrace() {
    String traceOnly = fingerprints.traceFingerprint("trace-1", null);
    String span1 = fingerprints.traceFingerprint("trace-1", "span-1");
    String span2 = fingerprints.traceFingerprint("trace-1", "span-2");

    assertThat(traceOnly).isNotEqualTo(span1);
    assertThat(span1).isNotEqualTo(span2);
  }

  @Test
  void deploymentAndDependencyFingerprintsAreKeyedBySourceEventId() {
    String deployment = fingerprints.deploymentFingerprint("event-1");
    String dependency = fingerprints.dependencyFingerprint("event-1");

    // Same source event ID, different evidence type prefix -> different fingerprints, so a
    // deployment event and a dependency event can never collide even if IDs were reused.
    assertThat(deployment).isNotEqualTo(dependency);
  }
}
