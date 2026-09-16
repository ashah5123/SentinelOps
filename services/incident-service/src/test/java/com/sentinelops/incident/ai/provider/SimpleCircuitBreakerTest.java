package com.sentinelops.incident.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SimpleCircuitBreakerTest {

  @Test
  void staysClosedBelowTheFailureThreshold() {
    SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(3, Duration.ofSeconds(30));
    breaker.recordFailure();
    breaker.recordFailure();
    assertThat(breaker.isOpen()).isFalse();
    assertThat(breaker.allowRequest()).isTrue();
  }

  @Test
  void opensAtTheFailureThreshold() {
    SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(3, Duration.ofSeconds(30));
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordFailure();
    assertThat(breaker.isOpen()).isTrue();
    assertThat(breaker.allowRequest()).isFalse();
  }

  @Test
  void aSuccessResetsTheFailureCount() {
    SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(3, Duration.ofSeconds(30));
    breaker.recordFailure();
    breaker.recordFailure();
    breaker.recordSuccess();
    breaker.recordFailure();
    breaker.recordFailure();
    assertThat(breaker.isOpen()).isFalse();
  }

  @Test
  void transitionsToHalfOpenAndAllowsTrialCallsAfterTheOpenDurationElapses() {
    Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now[0];
          }
        };
    SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(1, Duration.ofSeconds(10), clock);
    breaker.recordFailure();
    assertThat(breaker.isOpen()).isTrue();
    assertThat(breaker.allowRequest()).isFalse();

    now[0] = now[0].plusSeconds(11);
    assertThat(breaker.allowRequest()).isTrue();
    // Once transitioned to HALF_OPEN, further calls are allowed through as trial calls until
    // recordSuccess/recordFailure resolves the state one way or the other.
    assertThat(breaker.allowRequest()).isTrue();
  }

  @Test
  void aFailureDuringTheHalfOpenTrialReopensImmediately() {
    Instant[] now = {Instant.parse("2026-01-01T00:00:00Z")};
    Clock clock =
        new Clock() {
          @Override
          public ZoneOffset getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            return this;
          }

          @Override
          public Instant instant() {
            return now[0];
          }
        };
    SimpleCircuitBreaker breaker = new SimpleCircuitBreaker(1, Duration.ofSeconds(10), clock);
    breaker.recordFailure();
    now[0] = now[0].plusSeconds(11);
    assertThat(breaker.allowRequest()).isTrue();

    breaker.recordFailure();
    assertThat(breaker.isOpen()).isTrue();
    assertThat(breaker.allowRequest()).isFalse();
  }
}
