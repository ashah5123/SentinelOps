package com.sentinelops.incident.alerts.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationRow(
    UUID id,
    UUID incidentId,
    String channel,
    String routingRuleId,
    int routingRuleVersion,
    String idempotencyKey,
    String status,
    int attemptCount,
    String lastError,
    String payloadJson,
    Instant createdAt,
    Instant sentAt,
    Instant nextAttemptAt) {}
