package com.sentinelops.incident.ai;

import java.time.Duration;

/**
 * Thrown when a triage request for the same incident arrives before the configured cooldown
 * elapses.
 */
public class AiRateLimitedException extends RuntimeException {

  private final Duration retryAfter;

  public AiRateLimitedException(Duration retryAfter) {
    super(
        "Another AI triage request for this incident was made too recently; retry after "
            + retryAfter);
    this.retryAfter = retryAfter;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
