package com.sentinelops.incident.alerts.notification;

import java.util.UUID;

/**
 * The only fields ever placed in a rendered notification (section 11) — never a secret, an HMAC
 * value, or the raw alert/event payload.
 */
public record NotificationPayload(
    UUID incidentId,
    String incidentNumber,
    String severity,
    String service,
    String environment,
    String summary,
    String incidentLink,
    String routingReason,
    String acknowledgeInstructions) {}
