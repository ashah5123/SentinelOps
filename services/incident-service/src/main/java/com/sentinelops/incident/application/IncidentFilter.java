package com.sentinelops.incident.application;

import com.sentinelops.incident.domain.IncidentSeverity;
import com.sentinelops.incident.domain.IncidentStatus;
import java.time.Instant;

/** Optional filters for listing incidents. Any field left {@code null} is not applied. */
public record IncidentFilter(
    IncidentStatus status,
    IncidentSeverity severity,
    String affectedService,
    Instant detectedFrom,
    Instant detectedTo) {}
