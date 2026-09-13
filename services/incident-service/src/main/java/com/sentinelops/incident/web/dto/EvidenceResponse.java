package com.sentinelops.incident.web.dto;

import com.sentinelops.incident.domain.IncidentEvidence;
import java.time.Instant;
import java.util.UUID;

public record EvidenceResponse(
    UUID id,
    UUID incidentId,
    String evidenceType,
    String description,
    String sourceReference,
    Instant recordedAt) {

  public static EvidenceResponse from(IncidentEvidence evidence) {
    return new EvidenceResponse(
        evidence.getId(),
        evidence.getIncidentId(),
        evidence.getEvidenceType(),
        evidence.getDescription(),
        evidence.getSourceReference(),
        evidence.getRecordedAt());
  }
}
