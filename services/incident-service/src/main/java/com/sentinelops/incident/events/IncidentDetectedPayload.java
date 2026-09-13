package com.sentinelops.incident.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/** Payload of a published {@code incident.detected.v1} event. */
public record IncidentDetectedPayload(
    @JsonProperty("incidentId") UUID incidentId,
    @JsonProperty("incidentNumber") String incidentNumber,
    @JsonProperty("title") String title,
    @JsonProperty("severity") String severity,
    @JsonProperty("affectedService") String affectedService,
    @JsonProperty("source") String source,
    @JsonProperty("detectedAt") Instant detectedAt) {}
