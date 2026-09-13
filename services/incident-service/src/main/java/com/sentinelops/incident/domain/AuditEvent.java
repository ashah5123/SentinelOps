package com.sentinelops.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An immutable audit record. Audit events are append-only at the application layer: no repository
 * method exists to update or delete one once written.
 */
@Entity
@Table(name = "audit_events", schema = "audit")
public class AuditEvent {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "incident_id", updatable = false)
  private UUID incidentId;

  @Column(name = "action", nullable = false, updatable = false, length = 100)
  private String action;

  @Enumerated(EnumType.STRING)
  @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
  private ActorType actorType;

  @Column(name = "actor_id", nullable = false, updatable = false, length = 100)
  private String actorId;

  @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
  private String correlationId;

  @Column(name = "occurred_at", nullable = false, updatable = false)
  private Instant occurredAt;

  /** Sanitized, non-sensitive metadata serialized as JSON. Never raw secrets. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "metadata", updatable = false)
  private String metadataJson;

  protected AuditEvent() {
    // required by JPA
  }

  private AuditEvent(
      UUID id,
      UUID incidentId,
      String action,
      ActorType actorType,
      String actorId,
      String correlationId,
      Instant occurredAt,
      String metadataJson) {
    this.id = id;
    this.incidentId = incidentId;
    this.action = action;
    this.actorType = actorType;
    this.actorId = actorId;
    this.correlationId = correlationId;
    this.occurredAt = occurredAt;
    this.metadataJson = metadataJson;
  }

  public static AuditEvent record(
      UUID incidentId,
      String action,
      ActorType actorType,
      String actorId,
      String correlationId,
      String metadataJson) {
    return new AuditEvent(
        UUID.randomUUID(),
        incidentId,
        action,
        actorType,
        actorId,
        correlationId,
        Instant.now(),
        metadataJson);
  }

  public UUID getId() {
    return id;
  }

  public UUID getIncidentId() {
    return incidentId;
  }

  public String getAction() {
    return action;
  }

  public ActorType getActorType() {
    return actorType;
  }

  public String getActorId() {
    return actorId;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Instant getOccurredAt() {
    return occurredAt;
  }

  public String getMetadataJson() {
    return metadataJson;
  }
}
