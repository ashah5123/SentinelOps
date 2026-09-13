package com.sentinelops.incident.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

/**
 * Payload of a consumed {@code telemetry.anomaly.v1} event, as published by the future detection
 * engine (see {@code docs/architecture/system-overview.md}). The incident service treats {@code
 * sourceEventId} (the enclosing envelope's {@code eventId}) as the idempotency key for
 * anomaly-driven incident creation.
 */
public record TelemetryAnomalyPayload(
    @JsonProperty("title") String title,
    @JsonProperty("description") String description,
    @JsonProperty("severity") String severity,
    @JsonProperty("affectedService") String affectedService,
    @JsonProperty("detectedAt") Instant detectedAt) {}
