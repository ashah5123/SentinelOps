package com.sentinelops.incident.config;

import java.util.Random;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Wraps {@link ExponentialBackOff} to add random jitter to every computed interval, so that many
 * consumers or partitions failing at the same moment (e.g. right after a broker outage ends) don't
 * all retry in lockstep and cause a synchronized retry storm.
 *
 * <p>{@code jitterFraction} is the maximum fractional deviation applied to each interval — e.g.
 * {@code 0.2} spreads a computed 1000ms interval uniformly across [800ms, 1200ms]. A fraction of
 * {@code 0.0} disables jitter and reproduces the plain {@link ExponentialBackOff} behavior exactly.
 */
public class JitteredExponentialBackOff implements BackOff {

  private final ExponentialBackOff delegate;
  private final double jitterFraction;
  private final Random random = new Random();

  public JitteredExponentialBackOff(
      long initialIntervalMillis,
      double multiplier,
      long maxIntervalMillis,
      long maxElapsedTimeMillis,
      double jitterFraction) {
    this.delegate = new ExponentialBackOff(initialIntervalMillis, multiplier);
    this.delegate.setMaxInterval(maxIntervalMillis);
    this.delegate.setMaxElapsedTime(maxElapsedTimeMillis);
    this.jitterFraction = jitterFraction;
  }

  @Override
  public BackOffExecution start() {
    BackOffExecution execution = delegate.start();
    return () -> {
      long next = execution.nextBackOff();
      if (next == BackOffExecution.STOP || jitterFraction <= 0) {
        return next;
      }
      long jitterRange = Math.round(next * jitterFraction);
      long jittered = next + (jitterRange == 0 ? 0 : nextLongInclusive(-jitterRange, jitterRange));
      return Math.max(0, jittered);
    };
  }

  private long nextLongInclusive(long lowerInclusive, long upperInclusive) {
    return lowerInclusive + Math.floorMod(random.nextLong(), upperInclusive - lowerInclusive + 1);
  }
}
