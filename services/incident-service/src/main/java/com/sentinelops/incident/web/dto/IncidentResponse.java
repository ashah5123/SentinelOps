package com.sentinelops.incident.web.dto;

import com.sentinelops.incident.domain.Incident;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "An incident, as exposed by the API. Never the underlying JPA entity.")
public record IncidentResponse(
    UUID id,
    String incidentNumber,
    String title,
    String description,
    String severity,
    String status,
    String source,
    String affectedService,
    Instant detectedAt,
    Instant createdAt,
    Instant updatedAt,
    Instant resolvedAt,
    String assigneeId,
    String correlationId,
    long version) {

  public static IncidentResponse from(Incident incident) {
    return new IncidentResponse(
        incident.getId(),
        incident.getIncidentNumber(),
        incident.getTitle(),
        incident.getDescription(),
        incident.getSeverity().name(),
        incident.getStatus().name(),
        incident.getSource(),
        incident.getAffectedService(),
        incident.getDetectedAt(),
        incident.getCreatedAt(),
        incident.getUpdatedAt(),
        incident.getResolvedAt(),
        incident.getAssigneeId(),
        incident.getCorrelationId(),
        incident.getVersion());
  }
}
