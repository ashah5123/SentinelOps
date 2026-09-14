package com.sentinelops.telemetry.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.UUID;

/**
 * Payload of {@code incident.evidence.correlated.v1}, published through this service's
 * transactional outbox for every piece of evidence a correlation run selects. See {@code
 * docs/events/telemetry-correlation-events.md}.
 *
 * <p>This event reports a correlation <b>score</b>, not a confirmed root cause — see the
 * correlation-engine documentation. {@code scoreExplanation} is a short, human-readable summary of
 * which rules contributed to {@code correlationScore}; the full per-rule breakdown is queryable
 * from this service's own API.
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
