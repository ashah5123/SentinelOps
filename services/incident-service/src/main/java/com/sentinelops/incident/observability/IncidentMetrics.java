package com.sentinelops.incident.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
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

  /** An outbox row exhausted its retry attempts and moved to the FAILED (dead-letter) state. */
  public void outboxDeadLettered(String topic) {
    Counter.builder("sentinelops.outbox.dead_lettered")
        .description("Outbox events that exhausted their retry attempts and moved to FAILED")
        .tag("topic", topic)
        .register(meterRegistry)
        .increment();
  }

  /** A consumed record was routed to its dead-letter topic (retries exhausted or non-retryable). */
  public void consumerDeadLettered(String topic) {
    Counter.builder("sentinelops.consumer.dead_lettered")
        .description("Consumed records routed to a dead-letter topic, by source topic")
        .tag("topic", topic)
        .register(meterRegistry)
        .increment();
  }

  /** One retry attempt was made for a record that initially failed processing. */
  public void consumerRetryAttempted(String topic) {
    Counter.builder("sentinelops.consumer.retry.attempts")
        .description("Consumer retry attempts, by source topic")
        .tag("topic", topic)
        .register(meterRegistry)
        .increment();
  }

  /** {@code outcome} is one of {@code processed}, {@code duplicate}, or {@code failed}. */
  public void evidenceCorrelatedProcessed(String outcome) {
    Counter.builder("sentinelops.evidence_correlated.events.processed")
        .description("incident.evidence.correlated.v1 events processed, by outcome")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  /**
   * Registers the outbox-backlog gauges once, backed by the given repository lookups. Called once
   * at startup by {@code OutboxBacklogMetrics} rather than on every publish, since a gauge is a
   * live callback rather than a point-in-time value.
   */
  public void bindOutboxBacklogGauges(
      Supplier<Number> pendingCount, Supplier<Number> oldestPendingAgeSeconds) {
    Gauge.builder("sentinelops.outbox.backlog", pendingCount)
        .description("Number of outbox rows currently PENDING publication")
        .register(meterRegistry);
    Gauge.builder("sentinelops.outbox.oldest_pending_age_seconds", oldestPendingAgeSeconds)
        .description(
            "Age in seconds of the oldest still-PENDING outbox row (0 when the backlog is empty);"
                + " also usable as a recovery-time proxy after a dependency outage ends")
        .register(meterRegistry);
  }
}
