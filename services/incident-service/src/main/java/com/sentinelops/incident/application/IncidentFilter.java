package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import java.time.Instant;

/**
 * Optional filters for listing incidents. Any field left {@code null} (or {@code false} for {@code
 * unassignedOnly}) is not applied. {@code assigneeId} and {@code unassignedOnly} are mutually
 * exclusive; if both are set, {@code unassignedOnly} takes precedence.
 */
public record IncidentFilter(
    IncidentStatus status,
    IncidentSeverity severity,
    String affectedService,
    Instant detectedFrom,
    Instant detectedTo,
    String assigneeId,
    boolean unassignedOnly) {

  public IncidentFilter(
      IncidentStatus status,
      IncidentSeverity severity,
      String affectedService,
      Instant detectedFrom,
      Instant detectedTo) {
    this(status, severity, affectedService, detectedFrom, detectedTo, null, false);
  }
}
