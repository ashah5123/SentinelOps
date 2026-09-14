package com.sentinelops.telemetry.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Custom SentinelOps telemetry-correlation-service metrics, in addition to what Spring Boot and
 * Micrometer already publish automatically (HTTP request count/duration, JVM memory/GC, database
 * connection-pool, Kafka client metrics, and Resilience4j circuit-breaker state — the latter via
 * {@code resilience4j_circuitbreaker_state} et al., auto-registered by resilience4j-spring-boot3).
 *
 * <p>Every tag here is a small, fixed value (source name, evidence type, outcome) — never an
 * evidence ID, incident ID, correlation ID, deployment ID, a runtime-supplied service name, or an
 * error message. See docs/development/observability.md for the full metric catalog.
 */
@Component
public class TelemetryMetrics {

  private final MeterRegistry meterRegistry;

  public TelemetryMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void recordIngested(String source, String evidenceType, int count) {
    if (count <= 0) {
      return;
    }
    Counter.builder("sentinelops.telemetry.records.ingested")
        .description("Normalized evidence records ingested, by source and evidence type")
        .tag("source", source)
        .tag("evidence_type", evidenceType)
        .register(meterRegistry)
        .increment(count);
  }

  public void recordDuplicateIgnored(String source, int count) {
    if (count <= 0) {
      return;
    }
    Counter.builder("sentinelops.telemetry.records.duplicate")
        .description("Records rejected as duplicates during ingestion, by source")
        .tag("source", source)
        .register(meterRegistry)
        .increment(count);
  }

  public Timer.Sample startPollingCycleTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopPollingCycleTimer(Timer.Sample sample, String source) {
    sample.stop(
        Timer.builder("sentinelops.telemetry.polling.duration")
            .description("Duration of one polling cycle, by source")
            .tag("source", source)
            .register(meterRegistry));
  }

  public void pollingFailed(String source) {
    Counter.builder("sentinelops.telemetry.polling.failures")
        .description("Polling cycles that failed before persistence completed, by source")
        .tag("source", source)
        .register(meterRegistry)
        .increment();
  }

  public void backendQueryFailed(String source) {
    Counter.builder("sentinelops.telemetry.backend.query.failures")
        .description("Backend query failures, by source")
        .tag("source", source)
        .register(meterRegistry)
        .increment();
  }

  /** {@code outcome} is one of {@code processed}, {@code duplicate}, or {@code failed}. */
  public void deploymentEventProcessed(String outcome) {
    Counter.builder("sentinelops.telemetry.deployment.events.processed")
        .description("Deployment-change events processed, by outcome")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  /** {@code operation} is one of {@code added}, {@code updated}, {@code removed}. */
  public void dependencyEventProcessed(String operation, String outcome) {
    Counter.builder("sentinelops.telemetry.dependency.events.processed")
        .description("Dependency-change events processed, by operation and outcome")
        .tag("operation", operation)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  /** {@code outcome} is one of {@code correlated}, {@code duplicate}, or {@code failed}. */
  public void incidentCorrelated(String outcome) {
    Counter.builder("sentinelops.telemetry.incidents.correlated")
        .description("Incident-detected events processed by the correlation engine, by outcome")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public void evidenceSelected(String evidenceType) {
    Counter.builder("sentinelops.telemetry.correlation.evidence.selected")
        .description("Evidence records selected by a correlation run, by evidence type")
        .tag("evidence_type", evidenceType)
        .register(meterRegistry)
        .increment();
  }

  public Timer.Sample startCorrelationTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopCorrelationTimer(Timer.Sample sample) {
    sample.stop(
        Timer.builder("sentinelops.telemetry.correlation.duration")
            .description("Time to evaluate one correlation run")
            .register(meterRegistry));
  }

  public Timer.Sample startOutboxPublishTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopOutboxPublishTimer(Timer.Sample sample, String topic) {
    sample.stop(
        Timer.builder("sentinelops.telemetry.outbox.publish.duration")
            .description("Time to publish one outbox event to Kafka")
            .tag("topic", topic)
            .register(meterRegistry));
  }

  /** {@code outcome} is one of {@code success} or {@code failure}. */
  public void outboxPublished(String topic, String outcome) {
    Counter.builder("sentinelops.telemetry.outbox.published")
        .description("Outbox events published, by topic and outcome (success|failure)")
        .tag("topic", topic)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  /** An outbox row exhausted its retry attempts and moved to the FAILED (dead-letter) state. */
  public void outboxDeadLettered(String topic) {
    Counter.builder("sentinelops.telemetry.outbox.dead_lettered")
        .description("Outbox events that exhausted their retry attempts and moved to FAILED")
        .tag("topic", topic)
        .register(meterRegistry)
        .increment();
  }

  /** A consumed record was routed to its dead-letter topic (retries exhausted or non-retryable). */
  public void consumerDeadLettered(String topic) {
    Counter.builder("sentinelops.telemetry.consumer.dead_lettered")
        .description("Consumed records routed to a dead-letter topic, by source topic")
        .tag("topic", topic)
        .register(meterRegistry)
        .increment();
  }

  /** One retry attempt was made for a record that initially failed processing. */
  public void consumerRetryAttempted(String topic) {
    Counter.builder("sentinelops.telemetry.consumer.retry.attempts")
        .description("Consumer retry attempts, by source topic")
        .tag("topic", topic)
        .register(meterRegistry)
        .increment();
  }

  /**
   * Registers the outbox-backlog gauges once, backed by the given repository lookups — see the
   * identical pattern in the incident service's {@code IncidentMetrics}.
   */
  public void bindOutboxBacklogGauges(
      Supplier<Number> pendingCount, Supplier<Number> oldestPendingAgeSeconds) {
    Gauge.builder("sentinelops.telemetry.outbox.backlog", pendingCount)
        .description("Number of outbox rows currently PENDING publication")
        .register(meterRegistry);
    Gauge.builder(
            "sentinelops.telemetry.outbox.oldest_pending_age_seconds", oldestPendingAgeSeconds)
        .description(
            "Age in seconds of the oldest still-PENDING outbox row (0 when the backlog is empty);"
                + " also usable as a recovery-time proxy after a dependency outage ends")
        .register(meterRegistry);
  }
}
