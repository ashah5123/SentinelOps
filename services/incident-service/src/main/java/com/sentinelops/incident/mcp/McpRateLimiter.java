package com.sentinelops.incident.mcp;

import com.sentinelops.incident.mcp.observability.McpMetrics;
import java.time.Instant;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;
import org.springframework.stereotype.Component;

/**
 * Per-identity request-rate and concurrency limiting for MCP tool calls (section 11) — the same
 * fixed-window-per-key approach as {@code ConnectorRateLimiter} (Phase 12), keyed by the
 * authenticated subject instead of connector type. A slow client is bounded by {@link
 * #tryAcquireConcurrencySlot} releasing promptly on cancellation/completion, so it can never pin an
 * unbounded number of backend threads.
 */
@Component
public class McpRateLimiter {

  private final McpProperties.RateLimit properties;
  private final McpMetrics metrics;
  private final ConcurrentHashMap<String, Deque<Instant>> requestTimestamps =
      new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Semaphore> concurrencySemaphores =
      new ConcurrentHashMap<>();

  public McpRateLimiter(McpProperties properties, McpMetrics metrics) {
    this.properties = properties.rateLimit();
    this.metrics = metrics;
  }

  public boolean tryAcquireRequest(String subject) {
    Deque<Instant> timestamps =
        requestTimestamps.computeIfAbsent(subject, k -> new ConcurrentLinkedDeque<>());
    Instant now = Instant.now();
    Instant windowStart = now.minus(properties.window());
    synchronized (timestamps) {
      while (!timestamps.isEmpty() && timestamps.peekFirst().isBefore(windowStart)) {
        timestamps.pollFirst();
      }
      if (timestamps.size() >= properties.maxRequestsPerWindow()) {
        metrics.rateLimited("request");
        return false;
      }
      timestamps.addLast(now);
      return true;
    }
  }

  public Semaphore concurrencySemaphore(String subject) {
    return concurrencySemaphores.computeIfAbsent(
        subject, k -> new Semaphore(properties.maxConcurrentToolCallsPerActor()));
  }

  public boolean tryAcquireConcurrencySlot(String subject) {
    boolean acquired = concurrencySemaphore(subject).tryAcquire();
    if (!acquired) {
      metrics.rateLimited("concurrency");
    }
    return acquired;
  }

  public void releaseConcurrencySlot(String subject) {
    concurrencySemaphore(subject).release();
  }
}
