package com.sentinelops.incident.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Custom SentinelOps application metrics, in addition to the metrics Spring Boot and Micrometer
 * already publish automatically (HTTP request count/duration, JVM memory/GC, database
 * connection-pool, Kafka client metrics).
 *
 * <p>Every tag used here is a small, fixed enum-like value (severity, status name, outcome, topic)
 * — never an incident ID, correlation ID, exception message, or other unbounded, user-controlled
 * value. See docs/development/observability.md for the full metric catalog.
 */
@Component
public class IncidentMetrics {

  private final MeterRegistry meterRegistry;

  public IncidentMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void incidentCreated(String severity) {
    Counter.builder("sentinelops.incidents.created")
        .description("Incidents created, by severity")
        .tag("severity", severity)
        .register(meterRegistry)
        .increment();
  }

  public void incidentTransitioned(String fromStatus, String toStatus) {
    Counter.builder("sentinelops.incidents.transitioned")
        .description("Incident status transitions, by previous and new status")
        .tag("from_status", fromStatus)
        .tag("to_status", toStatus)
        .register(meterRegistry)
        .increment();
  }

  /** {@code outcome} is one of {@code created}, {@code duplicate}, or {@code failed}. */
  public void anomalyEventProcessed(String outcome) {
    Counter.builder("sentinelops.anomaly.events.processed")
        .description("Anomaly events processed, by outcome (created|duplicate|failed)")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  /** {@code outcome} is one of {@code success} or {@code failure}. */
  public void outboxPublished(String topic, String outcome) {
    Counter.builder("sentinelops.outbox.published")
        .description(
            "Transactional outbox events published, by topic and outcome (success|failure)")
        .tag("topic", topic)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public Timer.Sample startOutboxPublishTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopOutboxPublishTimer(Timer.Sample sample, String topic) {
    sample.stop(
        Timer.builder("sentinelops.outbox.publish.duration")
            .description("Time to publish one outbox event to Kafka")
            .tag("topic", topic)
            .register(meterRegistry));
  }
}
