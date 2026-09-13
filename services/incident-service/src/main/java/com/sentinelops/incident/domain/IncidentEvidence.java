package com.sentinelops.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A single piece of evidence recorded against an incident during investigation. */
@Entity
@Table(name = "incident_evidence", schema = "incidents")
public class IncidentEvidence {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "incident_id", nullable = false, updatable = false)
  private UUID incidentId;

  @Column(name = "evidence_type", nullable = false, length = 50)
  private String evidenceType;

  @Column(name = "description", nullable = false)
  private String description;

  @Column(name = "source_reference", length = 500)
  private String sourceReference;

  @Column(name = "correlation_id", nullable = false, length = 64)
  private String correlationId;

  @Column(name = "recorded_at", nullable = false, updatable = false)
  private Instant recordedAt;

  protected IncidentEvidence() {
    // required by JPA
  }

  private IncidentEvidence(
      UUID id,
      UUID incidentId,
      String evidenceType,
      String description,
      String sourceReference,
      String correlationId,
      Instant recordedAt) {
    this.id = id;
    this.incidentId = incidentId;
    this.evidenceType = evidenceType;
    this.description = description;
    this.sourceReference = sourceReference;
    this.correlationId = correlationId;
    this.recordedAt = recordedAt;
  }

  public static IncidentEvidence record(
      UUID incidentId,
      String evidenceType,
      String description,
      String sourceReference,
      String correlationId) {
    return new IncidentEvidence(
        UUID.randomUUID(),
        incidentId,
        evidenceType,
        description,
        sourceReference,
        correlationId,
        Instant.now());
  }

  public UUID getId() {
    return id;
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public String getEvidenceType() {
    return evidenceType;
  }

  public String getDescription() {
    return description;
  }

  public String getSourceReference() {
    return sourceReference;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }
}
