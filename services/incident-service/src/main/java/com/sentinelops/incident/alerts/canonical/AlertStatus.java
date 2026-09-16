package com.sentinelops.incident.alerts.canonical;

/**
 * Canonical alert lifecycle status — every connector maps its own vocabulary onto exactly these two
 * values.
 */
public enum AlertStatus {
  FIRING,
  RESOLVED
}
