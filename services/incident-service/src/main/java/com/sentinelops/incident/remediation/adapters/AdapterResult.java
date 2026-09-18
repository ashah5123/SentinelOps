package com.sentinelops.incident.remediation.adapters;

import java.util.List;

/**
 * The result of either planning (dry run) or executing one adapter call. {@code affectedResources}
 * is always populated — even for a plan — so a dry run can honestly show "what would be touched."
 */
public record AdapterResult(boolean success, String message, List<String> affectedResources) {

  public static AdapterResult success(String message, List<String> affectedResources) {
    return new AdapterResult(true, message, affectedResources);
  }

  public static AdapterResult failure(String message, List<String> affectedResources) {
    return new AdapterResult(false, message, affectedResources);
  }
}
