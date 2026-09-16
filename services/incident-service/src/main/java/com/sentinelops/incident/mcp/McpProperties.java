package com.sentinelops.incident.mcp;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 13 MCP server configuration. Disabled by default (network transport) — exposing a network
 * MCP endpoint is an explicit operator opt-in, never an implicit consequence of starting the
 * service (section 3: "do not expose a public unauthenticated MCP endpoint"). Proposal rate
 * limiting lives in {@code ProposalProperties} instead of here, since proposals can also be created
 * through the plain REST console endpoint, not only through MCP.
 */
@ConfigurationProperties(prefix = "sentinelops.mcp")
@Validated
public class McpProperties {

  private final boolean networkTransportEnabled;
  @NotBlank private final String endpointPath;

  @Min(1)
  private final int maxPageSize;

  @Min(1)
  private final int maxSearchResults;

  @Min(1)
  private final int maxResponseBytes;

  private final RateLimit rateLimit;
  private final Duration toolTimeout;

  public McpProperties(
      boolean networkTransportEnabled,
      String endpointPath,
      int maxPageSize,
      int maxSearchResults,
      int maxResponseBytes,
      RateLimit rateLimit,
      Duration toolTimeout) {
    this.networkTransportEnabled = networkTransportEnabled;
    this.endpointPath = endpointPath;
    this.maxPageSize = maxPageSize;
    this.maxSearchResults = maxSearchResults;
    this.maxResponseBytes = maxResponseBytes;
    this.rateLimit = rateLimit;
    this.toolTimeout = toolTimeout;
  }

  public boolean networkTransportEnabled() {
    return networkTransportEnabled;
  }

  public String endpointPath() {
    return endpointPath;
  }

  public int maxPageSize() {
    return maxPageSize;
  }

  public int maxSearchResults() {
    return maxSearchResults;
  }

  public int maxResponseBytes() {
    return maxResponseBytes;
  }

  public RateLimit rateLimit() {
    return rateLimit;
  }

  public Duration toolTimeout() {
    return toolTimeout;
  }

  public static class RateLimit {
    @Min(1)
    private final int maxRequestsPerWindow;

    private final Duration window;

    @Min(1)
    private final int maxConcurrentToolCallsPerActor;

    public RateLimit(
        int maxRequestsPerWindow, Duration window, int maxConcurrentToolCallsPerActor) {
      this.maxRequestsPerWindow = maxRequestsPerWindow;
      this.window = window;
      this.maxConcurrentToolCallsPerActor = maxConcurrentToolCallsPerActor;
    }

    public int maxRequestsPerWindow() {
      return maxRequestsPerWindow;
    }

    public Duration window() {
      return window;
    }

    public int maxConcurrentToolCallsPerActor() {
      return maxConcurrentToolCallsPerActor;
    }
  }
}
