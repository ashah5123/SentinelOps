package com.sentinelops.incident.alerts.web;

import java.time.Instant;

/**
 * Administrator-only connector health summary (section 13) — never a secret, HMAC value, or
 * payload.
 */
public record ConnectorHealthResponse(
    String name,
    boolean enabled,
    Instant lastSuccessfulIngestion,
    long recentFailureCount,
    Instant lastSuccessfulNotification,
    long deadLetterCount,
    boolean configurationValid) {}
