package com.sentinelops.incident.alerts.web;

import com.sentinelops.incident.alerts.correlation.AlertCorrelationRepository.AlertCorrelationRow;
import java.time.Instant;
import java.util.UUID;

public record AlertCorrelationResponse(
    UUID id,
    UUID alertEventId,
    String ruleId,
    int ruleVersion,
    String matchedFields,
    String explanation,
    Instant correlatedAt) {

  public static AlertCorrelationResponse from(AlertCorrelationRow row) {
    return new AlertCorrelationResponse(
        row.id(),
        row.alertEventId(),
        row.ruleId(),
        row.ruleVersion(),
        row.matchedFieldsJson(),
        row.explanation(),
        row.correlatedAt());
  }
}
