package com.sentinelops.incident.ai.provider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A small, dependency-free circuit breaker (CLOSED -&gt; OPEN -&gt; HALF_OPEN -&gt; CLOSED), used
 * instead of pulling in resilience4j or Spring Cloud Circuit Breaker purely for this one provider
 * call — no such library is already a dependency of this service (see pom.xml), and adding one just
 * to gate a single HTTP call would be exactly the "tech added merely to expand the list" this
 * repository's conventions avoid. Reuses the same jittered-backoff spirit as {@code
 * JitteredExponentialBackOff} elsewhere in this service, at a much smaller scope.
 *
 * <p>Thread-safe: a single instance is shared across concurrent triage requests for one provider.
 */
public class SimpleCircuitBreaker {

  private enum State {
    CLOSED,
    OPEN,
    HALF_OPEN
  }

  private final int failureThreshold;
  private final Duration openDuration;
  private final Clock clock;

  private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
  private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
  private final AtomicReference<Instant> openedAt = new AtomicReference<>(Instant.EPOCH);

  public SimpleCircuitBreaker(int failureThreshold, Duration openDuration) {
    this(failureThreshold, openDuration, Clock.systemUTC());
  }

  public SimpleCircuitBreaker(int failureThreshold, Duration openDuration, Clock clock) {
    this.failureThreshold = failureThreshold;
    this.openDuration = openDuration;
    this.clock = clock;
  }

  /**
   * Whether a call should be allowed through right now (also transitions OPEN -&gt; HALF_OPEN when
   * due).
   */
  public boolean allowRequest() {
    State current = state.get();
    if (current == State.CLOSED || current == State.HALF_OPEN) {
      return true;
    }
    // OPEN: allow exactly one trial call once the open duration has elapsed.
    if (Duration.between(openedAt.get(), Instant.now(clock)).compareTo(openDuration) >= 0) {
      return state.compareAndSet(State.OPEN, State.HALF_OPEN);
    }
    return false;
  }

  public void recordSuccess() {
    consecutiveFailures.set(0);
    state.set(State.CLOSED);
  }

  public void recordFailure() {
    if (state.get() == State.HALF_OPEN) {
      open();
      return;
    }
    int failures = consecutiveFailures.incrementAndGet();
    if (failures >= failureThreshold) {
      open();
    }
  }

  public boolean isOpen() {
    return state.get() == State.OPEN;
  }

  private void open() {
    openedAt.set(Instant.now(clock));
    state.set(State.OPEN);
    consecutiveFailures.set(0);
  }
}
