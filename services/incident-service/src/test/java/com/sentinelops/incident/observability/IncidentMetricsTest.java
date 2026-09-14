package com.sentinelops.incident.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class IncidentMetricsTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final IncidentMetrics metrics = new IncidentMetrics(registry);

  @Test
  void incidentCreatedRecordsACounterTaggedBySeverity() {
    metrics.incidentCreated(IncidentSeverity.SEV1.name());
    metrics.incidentCreated(IncidentSeverity.SEV1.name());
    metrics.incidentCreated(IncidentSeverity.SEV4.name());

    Counter sev1 = registry.find("sentinelops.incidents.created").tag("severity", "SEV1").counter();
    Counter sev4 = registry.find("sentinelops.incidents.created").tag("severity", "SEV4").counter();

    assertThat(sev1).isNotNull();
    assertThat(sev1.count()).isEqualTo(2.0);
    assertThat(sev4).isNotNull();
    assertThat(sev4.count()).isEqualTo(1.0);
  }

  @Test
  void incidentTransitionedRecordsFromAndToStatusTags() {
    metrics.incidentTransitioned(
        IncidentStatus.DETECTED.name(), IncidentStatus.INVESTIGATING.name());

    Counter counter =
        registry
            .find("sentinelops.incidents.transitioned")
            .tag("from_status", "DETECTED")
            .tag("to_status", "INVESTIGATING")
            .counter();

    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);
  }

  @Test
  void anomalyEventProcessedDistinguishesOutcomes() {
    metrics.anomalyEventProcessed("created");
    metrics.anomalyEventProcessed("duplicate");
    metrics.anomalyEventProcessed("duplicate");
    metrics.anomalyEventProcessed("failed");

    assertThat(counterValue("sentinelops.anomaly.events.processed", "outcome", "created"))
        .isEqualTo(1.0);
    assertThat(counterValue("sentinelops.anomaly.events.processed", "outcome", "duplicate"))
        .isEqualTo(2.0);
    assertThat(counterValue("sentinelops.anomaly.events.processed", "outcome", "failed"))
        .isEqualTo(1.0);
  }

  @Test
  void outboxPublishedDistinguishesSuccessAndFailureByTopic() {
    metrics.outboxPublished("incident.detected.v1", "success");
    metrics.outboxPublished("incident.detected.v1", "failure");
    metrics.outboxPublished("audit.event.v1", "success");

    assertThat(
            registry
                .find("sentinelops.outbox.published")
                .tag("topic", "incident.detected.v1")
                .tag("outcome", "success")
                .counter()
                .count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find("sentinelops.outbox.published")
                .tag("topic", "incident.detected.v1")
                .tag("outcome", "failure")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void outboxPublishDurationRecordsATimerPerTopic() {
    Timer.Sample sample = metrics.startOutboxPublishTimer();
    metrics.stopOutboxPublishTimer(sample, "incident.detected.v1");

    Timer timer =
        registry
            .find("sentinelops.outbox.publish.duration")
            .tag("topic", "incident.detected.v1")
            .timer();

    assertThat(timer).isNotNull();
    assertThat(timer.count()).isEqualTo(1L);
  }

  @Test
  void repeatedCallsWithTheSameTagsDoNotCreateDuplicateMeters() {
    for (int i = 0; i < 25; i++) {
      metrics.incidentCreated(IncidentSeverity.SEV2.name());
    }

    long meterCount =
        registry.getMeters().stream()
            .filter(m -> m.getId().getName().equals("sentinelops.incidents.created"))
            .count();

    // One meter per distinct severity value, regardless of call count — proves the label
    // stays low-cardinality rather than growing per invocation.
    assertThat(meterCount).isEqualTo(1);
  }

  private double counterValue(String name, String tagKey, String tagValue) {
    Counter counter = registry.find(name).tag(tagKey, tagValue).counter();
    assertThat(counter).as("counter %s{%s=%s}", name, tagKey, tagValue).isNotNull();
    return counter.count();
  }
}
