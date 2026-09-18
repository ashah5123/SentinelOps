package com.sentinelops.incident.remediation;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 14 remediation-engine configuration. Every allowlist here is a hard boundary enforced by
 * the corresponding adapter — a runbook or proposal can never reach a cache namespace, queue name,
 * or diagnostic command outside these lists (section: "Safe action adapters").
 */
@ConfigurationProperties(prefix = "sentinelops.remediation")
@Validated
public class RemediationProperties {

  @NotEmpty private final List<String> allowedCacheNamespaces;
  @NotEmpty private final List<String> allowedQueueNames;
  @NotEmpty private final List<String> allowedDiagnosticCommands;

  @Min(1)
  private final int maxConcurrentExecutions;

  private final Duration lockLeaseDuration;
  private final Duration schedulerPollingInterval;

  @Min(1)
  private final int schedulerBatchSize;

  private final Duration diagnosticCommandTimeout;
  private final BlastRadius blastRadius;
  private final CircuitBreakerSettings circuitBreaker;
  private final MaintenanceWindow maintenanceWindow;

  public RemediationProperties(
      List<String> allowedCacheNamespaces,
      List<String> allowedQueueNames,
      List<String> allowedDiagnosticCommands,
      int maxConcurrentExecutions,
      Duration lockLeaseDuration,
      Duration schedulerPollingInterval,
      int schedulerBatchSize,
      Duration diagnosticCommandTimeout,
      BlastRadius blastRadius,
      CircuitBreakerSettings circuitBreaker,
      MaintenanceWindow maintenanceWindow) {
    this.allowedCacheNamespaces = allowedCacheNamespaces;
    this.allowedQueueNames = allowedQueueNames;
    this.allowedDiagnosticCommands = allowedDiagnosticCommands;
    this.maxConcurrentExecutions = maxConcurrentExecutions;
    this.lockLeaseDuration = lockLeaseDuration;
    this.schedulerPollingInterval = schedulerPollingInterval;
    this.schedulerBatchSize = schedulerBatchSize;
    this.diagnosticCommandTimeout = diagnosticCommandTimeout;
    this.blastRadius = blastRadius;
    this.circuitBreaker = circuitBreaker;
    this.maintenanceWindow = maintenanceWindow;
  }

  public List<String> allowedCacheNamespaces() {
    return allowedCacheNamespaces;
  }

  public List<String> allowedQueueNames() {
    return allowedQueueNames;
  }

  public List<String> allowedDiagnosticCommands() {
    return allowedDiagnosticCommands;
  }

  public int maxConcurrentExecutions() {
    return maxConcurrentExecutions;
  }

  public Duration lockLeaseDuration() {
    return lockLeaseDuration;
  }

  public Duration schedulerPollingInterval() {
    return schedulerPollingInterval;
  }

  public int schedulerBatchSize() {
    return schedulerBatchSize;
  }

  public Duration diagnosticCommandTimeout() {
    return diagnosticCommandTimeout;
  }

  public BlastRadius blastRadius() {
    return blastRadius;
  }

  public CircuitBreakerSettings circuitBreaker() {
    return circuitBreaker;
  }

  public MaintenanceWindow maintenanceWindow() {
    return maintenanceWindow;
  }

  /** Configurable blast-radius limits (section: "Build the remediation engine"). */
  public static class BlastRadius {
    @Min(1)
    private final int maxResourceCount;

    @NotEmpty private final List<String> restrictedEnvironments;

    public BlastRadius(int maxResourceCount, List<String> restrictedEnvironments) {
      this.maxResourceCount = maxResourceCount;
      this.restrictedEnvironments = restrictedEnvironments;
    }

    public int maxResourceCount() {
      return maxResourceCount;
    }

    /**
     * Environments (e.g. "production") where every execution requires the full approval count
     * regardless of computed risk.
     */
    public List<String> restrictedEnvironments() {
      return restrictedEnvironments;
    }
  }

  public static class CircuitBreakerSettings {
    @Min(1)
    private final int failureThreshold;

    private final Duration openDuration;

    public CircuitBreakerSettings(int failureThreshold, Duration openDuration) {
      this.failureThreshold = failureThreshold;
      this.openDuration = openDuration;
    }

    public int failureThreshold() {
      return failureThreshold;
    }

    public Duration openDuration() {
      return openDuration;
    }
  }

  /**
   * A fixed weekly window (e.g. Saturday nights) during which HIGH-risk actions in a restricted
   * environment are permitted, mirroring {@code BusinessHoursEvaluator}'s (Phase 12) day/hour check
   * but for the opposite purpose — this narrows rather than widens what is allowed.
   */
  public static class MaintenanceWindow {
    @NotEmpty private final List<String> daysOfWeek;

    @Min(0)
    private final int startHour;

    @Min(0)
    private final int endHour;

    private final String zoneId;

    public MaintenanceWindow(List<String> daysOfWeek, int startHour, int endHour, String zoneId) {
      this.daysOfWeek = daysOfWeek;
      this.startHour = startHour;
      this.endHour = endHour;
      this.zoneId = zoneId;
    }

    public List<String> daysOfWeek() {
      return daysOfWeek;
    }

    public int startHour() {
      return startHour;
    }

    public int endHour() {
      return endHour;
    }

    public String zoneId() {
      return zoneId;
    }
  }
}
