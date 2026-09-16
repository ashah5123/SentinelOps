package com.sentinelops.incident.alerts.escalation;

import java.time.Instant;
import java.util.UUID;

public record EscalationRow(
    UUID id,
    UUID incidentId,
    String routingRuleId,
    int routingRuleVersion,
    Instant scheduledAt,
    String status,
    Instant deliveredAt,
    Instant cancelledAt,
    String cancelledReason,
    Instant createdAt) {}
