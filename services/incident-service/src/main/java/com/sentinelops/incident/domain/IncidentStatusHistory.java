package com.sentinelops.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** An immutable record of a single incident status transition. */
@Entity
@Table(name = "incident_status_history", schema = "incidents")
public class IncidentStatusHistory {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "incident_id", nullable = false, updatable = false)
  private UUID incidentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 20)
  private IncidentStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 20)
  private IncidentStatus toStatus;

  @Column(name = "reason", length = 500)
  private String reason;

  @Column(name = "correlation_id", nullable = false, length = 64)
  private String correlationId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  protected IncidentStatusHistory() {
    // required by JPA
  }

  private IncidentStatusHistory(
      UUID id,
      UUID incidentId,
      IncidentStatus fromStatus,
      IncidentStatus toStatus,
      String reason,
      String correlationId,
      Instant occurredAt) {
    this.id = id;
    this.incidentId = incidentId;
    this.fromStatus = fromStatus;
    this.toStatus = toStatus;
    this.reason = reason;
    this.correlationId = correlationId;
    this.occurredAt = occurredAt;
  }

  public static IncidentStatusHistory record(
      UUID incidentId,
      IncidentStatus fromStatus,
      IncidentStatus toStatus,
      String reason,
      String correlationId) {
    return new IncidentStatusHistory(
        UUID.randomUUID(), incidentId, fromStatus, toStatus, reason, correlationId, Instant.now());
  }

  public UUID getId() {
    return id;
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public IncidentStatus getFromStatus() {
    return fromStatus;
  }

  public IncidentStatus getToStatus() {
    return toStatus;
  }

  public String getReason() {
    return reason;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }
}
