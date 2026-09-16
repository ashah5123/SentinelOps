package com.sentinelops.incident.ai;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * AI-triage metrics, following the same convention as {@link
 * com.sentinelops.incident.observability.IncidentMetrics}: every tag is a small, fixed value
 * (provider name, outcome, review decision) — never an incident ID, prompt content, or model
 * output.
 */
@Component
public class AiMetrics {

  private final MeterRegistry meterRegistry;

  public AiMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void triageRequested() {
    Counter.builder("sentinelops.ai.triage.requests").register(meterRegistry).increment();
  }

  /** {@code outcome} is one of {@code completed}, {@code failed}, {@code model_unavailable}. */
  public void triageCompleted(String providerName, String outcome) {
    Counter.builder("sentinelops.ai.triage.completed")
        .tag("provider", providerName)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public void invalidStructuredOutput(String providerName) {
    Counter.builder("sentinelops.ai.triage.invalid_output")
        .tag("provider", providerName)
        .register(meterRegistry)
        .increment();
  }

  public void providerTimeout(String providerName) {
    Counter.builder("sentinelops.ai.provider.timeouts")
        .tag("provider", providerName)
        .register(meterRegistry)
        .increment();
  }

  public void circuitBreakerRejected(String providerName) {
    Counter.builder("sentinelops.ai.provider.circuit_open")
        .tag("provider", providerName)
        .register(meterRegistry)
        .increment();
  }

  public void retrievalResultCount(int count) {
    Counter.builder("sentinelops.ai.retrieval.results").register(meterRegistry).increment(count);
  }

  /** {@code decision} is one of {@code accepted}, {@code rejected}, {@code partial}. */
  public void reviewRecorded(String decision) {
    Counter.builder("sentinelops.ai.triage.reviews")
        .tag("decision", decision)
        .register(meterRegistry)
        .increment();
  }

  public void rateLimited() {
    Counter.builder("sentinelops.ai.triage.rate_limited").register(meterRegistry).increment();
  }

  public Timer.Sample startTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopTriageTimer(Timer.Sample sample) {
    sample.stop(Timer.builder("sentinelops.ai.triage.duration").register(meterRegistry));
  }

  public void stopRetrievalTimer(Timer.Sample sample) {
    sample.stop(Timer.builder("sentinelops.ai.retrieval.duration").register(meterRegistry));
  }
}
