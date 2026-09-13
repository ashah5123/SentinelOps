package com.sentinelops.incident.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/** Payload of a published {@code audit.event.v1} event, mirroring an {@code AuditEvent} row. */
public record AuditEventPayload(
    @JsonProperty("auditEventId") UUID auditEventId,
    @JsonProperty("incidentId") UUID incidentId,
    @JsonProperty("action") String action,
    @JsonProperty("actorType") String actorType,
    @JsonProperty("actorId") String actorId) {}
