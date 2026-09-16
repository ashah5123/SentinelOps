package com.sentinelops.incident.mcp.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * Phase 13 MCP metrics. Every label is a small, fixed value (bounded tool/resource name, outcome,
 * reason code) — never an incident ID, proposal ID, user ID, token, query, or error message
 * (section 13).
 */
@Component
public class McpMetrics {

  private final MeterRegistry meterRegistry;
  private final AtomicInteger activeSessions = new AtomicInteger(0);

  public McpMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    meterRegistry.gauge("sentinelops.mcp.sessions.active", activeSessions);
  }

  public void sessionOpened() {
    activeSessions.incrementAndGet();
  }

  public void sessionClosed() {
    activeSessions.updateAndGet(v -> Math.max(0, v - 1));
  }

  /**
   * {@code outcome} is one of {@code success}, {@code denied}, {@code error}, {@code cancelled},
   * {@code invalid_arguments}.
   */
  public void toolCall(String toolName, String outcome) {
    Counter.builder("sentinelops.mcp.tool.calls")
        .tag("tool", toolName)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public void resourceRead(String resourceType, String outcome) {
    Counter.builder("sentinelops.mcp.resource.reads")
        .tag("resource_type", resourceType)
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public void authenticationFailure() {
    Counter.builder("sentinelops.mcp.auth.failures").register(meterRegistry).increment();
  }

  public void authorizationDenied(String toolOrResource) {
    Counter.builder("sentinelops.mcp.authz.denied")
        .tag("target", toolOrResource)
        .register(meterRegistry)
        .increment();
  }

  public Timer.Sample startToolTimer() {
    return Timer.start(meterRegistry);
  }

  public void stopToolTimer(Timer.Sample sample, String toolName) {
    sample.stop(
        Timer.builder("sentinelops.mcp.tool.duration")
            .tag("tool", toolName)
            .register(meterRegistry));
  }

  public void toolCancelled(String toolName) {
    Counter.builder("sentinelops.mcp.tool.cancelled")
        .tag("tool", toolName)
        .register(meterRegistry)
        .increment();
  }

  public void rateLimited(String limitType) {
    Counter.builder("sentinelops.mcp.rate_limited")
        .tag("limit_type", limitType)
        .register(meterRegistry)
        .increment();
  }

  /**
   * {@code outcome} is one of {@code created}, {@code approved}, {@code rejected}, {@code expired},
   * {@code stale}.
   */
  public void proposal(String outcome) {
    Counter.builder("sentinelops.mcp.proposals")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  /** {@code outcome} is one of {@code success}, {@code failed}, {@code replay_blocked}. */
  public void executionAttempt(String outcome) {
    Counter.builder("sentinelops.mcp.executions")
        .tag("outcome", outcome)
        .register(meterRegistry)
        .increment();
  }

  public void invalidArguments(String toolName) {
    Counter.builder("sentinelops.mcp.tool.invalid_arguments")
        .tag("tool", toolName)
        .register(meterRegistry)
        .increment();
  }

  public void promptInjectionTestFailure(String testCase) {
    Counter.builder("sentinelops.mcp.prompt_injection.failures")
        .tag("test_case", testCase)
        .register(meterRegistry)
        .increment();
  }

  public void backendDependencyFailure(String dependency) {
    Counter.builder("sentinelops.mcp.backend_dependency.failures")
        .tag("dependency", dependency)
        .register(meterRegistry)
        .increment();
  }
}
