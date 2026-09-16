package com.sentinelops.incident.proposal;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Agent-proposal rate limiting (section 11) — applies whether a proposal came from an MCP tool call
 * or the console's REST endpoint.
 */
@ConfigurationProperties(prefix = "sentinelops.proposals")
@Validated
public class ProposalProperties {

  @Min(1)
  private final int maxPerActorPerWindow;

  private final Duration window;

  public ProposalProperties(int maxPerActorPerWindow, Duration window) {
    this.maxPerActorPerWindow = maxPerActorPerWindow;
    this.window = window;
  }

  public int maxPerActorPerWindow() {
    return maxPerActorPerWindow;
  }

  public Duration window() {
    return window;
  }
}
