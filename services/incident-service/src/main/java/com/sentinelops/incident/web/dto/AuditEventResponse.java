package com.sentinelops.incident.web.dto;

import com.sentinelops.incident.domain.AuditEvent;
import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
    UUID id,
    UUID incidentId,
    String action,
    String actorType,
    String actorId,
    String correlationId,
    Instant occurredAt) {

  public static AuditEventResponse from(AuditEvent auditEvent) {
    return new AuditEventResponse(
        auditEvent.getId(),
        auditEvent.getIncidentId(),
        auditEvent.getAction(),
        auditEvent.getActorType().name(),
        auditEvent.getActorId(),
        auditEvent.getCorrelationId(),
        auditEvent.getOccurredAt());
  }
}
