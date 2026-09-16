package com.sentinelops.incident.alerts.web;

import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.observability.AlertMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiting per connector type (section 4). Deliberately simple (an in-memory
 * sliding count per connector, not per-caller/IP — every alert from one connector type shares one
 * budget) rather than a general-purpose limiter library, matching this codebase's preference for
 * small, purpose-built utilities (see {@code SimpleCircuitBreaker}, Phase 11).
 */
@Component
public class ConnectorRateLimiter {

  private final AlertsProperties.RateLimit properties;
  private final AlertMetrics metrics;
  private final ConcurrentHashMap<String, Deque<Instant>> requestTimestamps =
      new ConcurrentHashMap<>();

  public ConnectorRateLimiter(AlertsProperties properties, AlertMetrics metrics) {
    this.properties = properties.rateLimit();
    this.metrics = metrics;
  }

  /**
   * Returns {@code true} if the request is allowed, {@code false} if the connector's rate limit was
   * exceeded.
   */
  public boolean tryAcquire(String connectorType) {
    Deque<Instant> timestamps =
        requestTimestamps.computeIfAbsent(connectorType, k -> new ConcurrentLinkedDeque<>());
    Instant now = Instant.now();
    Instant windowStart = now.minus(properties.window());

    synchronized (timestamps) {
      while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
        timestamps.pollFirst();
      }
      if (timestamps.size() >= properties.maxRequestsPerWindow()) {
        metrics.connectorRateLimited(connectorType);
        return false;
      }
      timestamps.addLast(now);
      return true;
    }
  }

  public Duration window() {
    return properties.window();
  }
}
