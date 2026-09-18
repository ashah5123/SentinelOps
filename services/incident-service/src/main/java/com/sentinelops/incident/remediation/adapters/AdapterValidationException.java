package com.sentinelops.incident.remediation.adapters;

/**
 * Thrown when a step's parameters fail an adapter's own validation — never executed, never even
 * planned.
 */
public class AdapterValidationException extends RuntimeException {
  public AdapterValidationException(String message) {
    super(message);
  }
}
