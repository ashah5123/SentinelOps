package com.sentinelops.incident.alerts.web;

import com.sentinelops.incident.alerts.notification.NotificationRow;
import java.time.Instant;
import java.util.UUID;

/**
 * Never includes the rendered payload's link/summary verbatim beyond what's already safe — see
 * {@code NotificationPayload}.
 */
public record NotificationResponse(
    UUID id,
    String channel,
    String routingRuleId,
    int routingRuleVersion,
    String status,
    int attemptCount,
    String lastError,
    Instant createdAt,
    Instant sentAt) {

  public static NotificationResponse from(NotificationRow row) {
    return new NotificationResponse(
        row.id(),
        row.channel(),
        row.routingRuleId(),
        row.routingRuleVersion(),
        row.status(),
        row.attemptCount(),
        row.lastError(),
        row.createdAt(),
        row.sentAt());
  }
}
