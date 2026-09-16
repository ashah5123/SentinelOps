package com.sentinelops.incident.alerts.web;

import com.sentinelops.incident.alerts.escalation.EscalationRow;
import java.time.Instant;
import java.util.UUID;

public record EscalationResponse(
    UUID id,
    String routingRuleId,
    int routingRuleVersion,
    Instant scheduledAt,
    String status,
    Instant deliveredAt,
    Instant cancelledAt,
    String cancelledReason) {

  public static EscalationResponse from(EscalationRow row) {
    return new EscalationResponse(
        row.id(),
        row.routingRuleId(),
        row.routingRuleVersion(),
        row.scheduledAt(),
        row.status(),
        row.deliveredAt(),
        row.cancelledAt(),
        row.cancelledReason());
  }
}
