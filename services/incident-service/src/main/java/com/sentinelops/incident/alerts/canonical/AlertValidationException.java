package com.sentinelops.incident.alerts.canonical;

import java.util.List;

/**
 * Thrown when a canonical alert fails validation — mapped to a 400 with a bounded, safe list of
 * violations.
 */
public class AlertValidationException extends RuntimeException {

  private final List<String> violations;

  public AlertValidationException(List<String> violations) {
    super("Alert failed validation: " + String.join("; ", violations));
    this.violations = List.copyOf(violations);
  }

  public List<String> violations() {
    return violations;
  }
}
