package com.sentinelops.incident.alerts.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * Phase 12 alert-ingestion/correlation/notification/escalation metrics. Every tag is a small, fixed
 * value (connector/source type, outcome, severity, channel) — never an incident ID, external alert
 * ID, fingerprint, user ID, URL, or error message, matching the existing {@code
 * IncidentMetrics}/{@code AiMetrics} convention.
 */
@Component
public class AlertMetrics {

  private final MeterRegistry meterRegistry;

  public AlertMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** {@code outcome} is one of {@code accepted}, {@code duplicate_delivery}, {@code rejected}. */
  public void alertIngested(String connectorType, String outcome) {
    Counter.builder("sentinelops.alerts.ingested")
        .tag("connector_type", connectorType)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  /**
   * One HTTP request accepted or rejected a whole batch validation-wise (section 3's bounded batch
   * summary).
   */
  public void batchProcessed(String connectorType, String outcome) {
    Counter.builder("sentinelops.alerts.batches")
        .tag("connector_type", connectorType)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public void semanticDuplicate(String connectorType) {
    Counter.builder("sentinelops.alerts.semantic_duplicates")
        .tag("connector_type", connectorType)
        .register(meterRegistry)
        .increment();
  }

  public void incidentCreatedFromAlert(String severity) {
    Counter.builder("sentinelops.alerts.incidents_created")
        .tag("severity", severity)
        .register(meterRegistry)
        .increment();
  }

  /** {@code outcome} is one of {@code correlated}, {@code ambiguous}, {@code no_match}. */
  public void correlationDecision(String outcome) {
    Counter.builder("sentinelops.alerts.correlation_decisions")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public void resolutionProcessed(String outcome) {
    Counter.builder("sentinelops.alerts.resolutions")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public Timer.Sample startIngestionTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopIngestionTimer(Timer.Sample sample, String connectorType) {
    sample.stop(
        Timer.builder("sentinelops.alerts.ingestion.duration")
            .tag("connector_type", connectorType)
            .register(meterRegistry));
  }

  public void stopProcessingTimer(Timer.Sample sample) {
    sample.stop(Timer.builder("sentinelops.alerts.processing.duration").register(meterRegistry));
  }

  /** {@code outcome} is one of {@code sent}, {@code failed}, {@code dead_lettered}. */
  public void notificationAttempted(String channel, String outcome) {
    Counter.builder("sentinelops.alerts.notifications.attempts")
        .tag("channel", channel)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public Timer.Sample startNotificationTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopNotificationTimer(Timer.Sample sample, String channel) {
    sample.stop(
        Timer.builder("sentinelops.alerts.notifications.duration")
            .tag("channel", channel)
            .register(meterRegistry));
  }

  /** {@code outcome} is one of {@code scheduled}, {@code delivered}, {@code cancelled}. */
  public void escalation(String outcome) {
    Counter.builder("sentinelops.alerts.escalations")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public void connectorRateLimited(String connectorType) {
    Counter.builder("sentinelops.alerts.rate_limited")
        .tag("connector_type", connectorType)
        .register(meterRegistry)
        .increment();
  }

  public void invalidSignature(String connectorType) {
    Counter.builder("sentinelops.alerts.invalid_signature")
        .tag("connector_type", connectorType)
        .register(meterRegistry)
        .increment();
  }

  public void replayRejected(String connectorType) {
    Counter.builder("sentinelops.alerts.replay_rejected")
        .tag("connector_type", connectorType)
        .register(meterRegistry)
        .increment();
  }
}
