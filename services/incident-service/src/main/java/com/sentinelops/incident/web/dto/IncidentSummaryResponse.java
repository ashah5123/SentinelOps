package com.sentinelops.incident.web.dto;

import com.sentinelops.incident.application.IncidentSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(
    description =
        "Bounded dashboard aggregation: counts only, matching the same filters as the incident "
            + "list endpoint.")
public record IncidentSummaryResponse(
    long total,
    long open,
    long unacknowledged,
    Map<String, Long> bySeverity,
    Map<String, Long> byStatus) {

  public static IncidentSummaryResponse from(IncidentSummary summary) {
    return new IncidentSummaryResponse(
        summary.total(),
        summary.open(),
        summary.unacknowledged(),
        summary.bySeverity(),
        summary.byStatus());
  }
}
