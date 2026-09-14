package com.sentinelops.telemetry.domain;

/** The kind of fact a normalized {@link Evidence} record represents. */
public enum EvidenceType {
  METRIC,
  LOG,
  TRACE,
  DEPLOYMENT,
  DEPENDENCY
}
