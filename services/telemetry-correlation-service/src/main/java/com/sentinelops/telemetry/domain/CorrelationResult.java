package com.sentinelops.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One correlation run's outcome for a single {@code incident.detected.v1} event: which evidence was
 * found relevant, and why (see {@link CorrelationEvidence}). Correlation is deterministic and
 * rule-based (see the ADR for this phase) — it never claims to establish causation, only proximity
 * and connection between the incident and the evidence found.
 */
@Entity
@Table(name = "correlation_results", schema = "telemetry")
public class CorrelationResult {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "incident_id", nullable = false, updatable = false)
  private UUID incidentId;

  @Column(name = "affected_service", nullable = false, updatable = false, length = 150)
  private String affectedService;

  @Column(name = "detected_at", nullable = false, updatable = false)
  private Instant detectedAt;

  @Column(name = "evaluated_at", nullable = false, updatable = false)
  private Instant evaluatedAt;

  @Column(name = "evidence_count", nullable = false, updatable = false)
  private int evidenceCount;

  @Column(
      name = "source_event_id",
      nullable = false,
      updatable = false,
      unique = true,
      length = 128)
  private String sourceEventId;

  @Column(name = "correlation_id", updatable = false, length = 128)
  private String correlationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CorrelationResult() {
    // required by JPA
  }

  public CorrelationResult(
      UUID incidentId,
      String affectedService,
      Instant detectedAt,
      int evidenceCount,
      String sourceEventId,
      String correlationId) {
    this.id = UUID.randomUUID();
    this.incidentId = incidentId;
    this.affectedService = affectedService;
    this.detectedAt = detectedAt;
    this.evaluatedAt = Instant.now();
    this.evidenceCount = evidenceCount;
    this.sourceEventId = sourceEventId;
    this.correlationId = correlationId;
    this.createdAt = this.evaluatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public String getAffectedService() {
    return affectedService;
  }

  public Instant getDetectedAt() {
    return detectedAt;
  }

  public Instant getEvaluatedAt() {
    return evaluatedAt;
  }

  public int getEvidenceCount() {
    return evidenceCount;
  }

  public String getSourceEventId() {
    return sourceEventId;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
