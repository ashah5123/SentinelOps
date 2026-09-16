package com.sentinelops.incident.alerts.web;

import java.util.List;

/**
 * A bounded, safe summary of a multi-alert webhook batch (section 3) — never echoes raw payload
 * content.
 */
public record IngestionBatchResponse(
    int totalAlerts, int accepted, int duplicates, int rejected, List<String> errors) {

  private static final int MAX_ERRORS = 10;

  public static IngestionBatchResponse of(
      int totalAlerts, int accepted, int duplicates, List<String> errors) {
    List<String> bounded = errors.size() > MAX_ERRORS ? errors.subList(0, MAX_ERRORS) : errors;
    return new IngestionBatchResponse(totalAlerts, accepted, duplicates, errors.size(), bounded);
  }
}
