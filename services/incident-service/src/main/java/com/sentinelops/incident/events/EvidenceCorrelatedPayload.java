package com.sentinelops.incident.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code incident.evidence.correlated.v1}, published by the telemetry-correlation
 * service (see {@code docs/events/telemetry-correlation-events.md}). Consumed here to append
 * evidence to the correct incident — see {@code EvidenceCorrelatedListener}.
 *
 * <p>{@code correlationScore} reflects rule-based proximity and connection to the incident, not a
 * confirmed root cause — never presented or logged as one.
 */
public record EvidenceCorrelatedPayload(
    @JsonProperty("incidentId") UUID incidentId,
    @JsonProperty("evidenceId") UUID evidenceId,
    @JsonProperty("evidenceType") String evidenceType,
    @JsonProperty("summary") String summary,
    @JsonProperty("sourceService") String sourceService,
    @JsonProperty("observedAt") Instant observedAt,
    @JsonProperty("sourceReference") String sourceReference,
    @JsonProperty("correlationScore") double correlationScore,
    @JsonProperty("scoreExplanation") String scoreExplanation,
    @JsonProperty("traceId") String traceId,
    @JsonProperty("deploymentId") UUID deploymentId) {}
