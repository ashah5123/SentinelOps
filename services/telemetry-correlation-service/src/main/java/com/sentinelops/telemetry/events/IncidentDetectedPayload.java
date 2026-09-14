package com.sentinelops.telemetry.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * The subset of the incident service's {@code incident.detected.v1} payload this service consumes
 * to trigger correlation. See {@code docs/events/incident-events.md} for the full, authoritative
 * shape published by the incident service; unknown fields are ignored on deserialization (see
 * {@code JacksonConfig}), so this service only declares what it uses.
 */
public record IncidentDetectedPayload(
    @JsonProperty("incidentId") UUID incidentId,
    @JsonProperty("incidentNumber") String incidentNumber,
    @JsonProperty("severity") String severity,
    @JsonProperty("affectedService") String affectedService,
    @JsonProperty("detectedAt") Instant detectedAt) {}
