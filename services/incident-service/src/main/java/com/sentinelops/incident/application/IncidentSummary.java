package com.sentinelops.incident.application;

import java.util.Map;

/**
 * Bounded dashboard aggregation (see {@link IncidentQueryService#getSummary}) — counts only, never
 * incident rows themselves.
 */
public record IncidentSummary(
    long total,
    long open,
    long unacknowledged,
    Map<String, Long> bySeverity,
    Map<String, Long> byStatus) {}
