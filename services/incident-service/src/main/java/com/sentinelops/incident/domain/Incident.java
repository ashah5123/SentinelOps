package com.sentinelops.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The incident aggregate root.
 *
 * <p>Status is never assigned directly by callers outside this class — every change goes through
 * {@link #transitionTo(IncidentStatus, String)}, which enforces {@link IncidentTransitions} and
 * records the effective timestamps (e.g. {@code resolvedAt}).
 */
@Entity
@Table(
    name = "incidents",
    schema = "incidents",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_incidents_incident_number", columnNames = "incident_number"),
      @UniqueConstraint(name = "uq_incidents_source_event_id", columnNames = "source_event_id")
    })
public class Incident {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "incident_number", nullable = false, updatable = false, length = 32)
  private String incidentNumber;

  @Column(name = "title", nullable = false, length = 200)
  private String title;

  @Column(name = "description")
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(name = "severity", nullable = false, length = 10)
  private IncidentSeverity severity;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private IncidentStatus status;

  @Column(name = "source", nullable = false, length = 100)
  private String source;

  @Column(name = "affected_service", nullable = false, length = 100)
  private String affectedService;

  @Column(name = "detected_at", nullable = false)
  private Instant detectedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "resolved_at")
  private Instant resolvedAt;

  /**
   * Stable subject ID (see {@code AuthenticatedActor}) of the operator currently assigned to this
   * incident, or {@code null} if unassigned. Orthogonal to lifecycle status: assignment tracks "who
   * is working this," not "what state is it in."
   */
  @Column(name = "assignee_id", length = 100)
  private String assigneeId;

  @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
  private String correlationId;

  /** Nullable: only anomaly-sourced incidents carry the originating event ID. */
  @Column(name = "source_event_id", updatable = false, length = 128)
  private String sourceEventId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected Incident() {
    // required by JPA
  }

  private Incident(
      UUID id,
      String incidentNumber,
      String title,
      String description,
      IncidentSeverity severity,
      String source,
      String affectedService,
      Instant detectedAt,
      String correlationId,
      String sourceEventId) {
    this.id = Objects.requireNonNull(id, "id");
    this.incidentNumber = Objects.requireNonNull(incidentNumber, "incidentNumber");
    this.title = Objects.requireNonNull(title, "title");
    this.description = description;
    this.severity = Objects.requireNonNull(severity, "severity");
    this.status = IncidentStatus.DETECTED;
    this.source = Objects.requireNonNull(source, "source");
    this.affectedService = Objects.requireNonNull(affectedService, "affectedService");
    this.detectedAt = Objects.requireNonNull(detectedAt, "detectedAt");
    this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
    this.sourceEventId = sourceEventId;
  }

  /** Creates a new incident in the initial {@link IncidentStatus#DETECTED} status. */
  public static Incident detect(
      UUID id,
      String incidentNumber,
      String title,
      String description,
      IncidentSeverity severity,
      String source,
      String affectedService,
      Instant detectedAt,
      String correlationId,
      String sourceEventId) {
    return new Incident(
        id,
        incidentNumber,
        title,
        description,
        severity,
        source,
        affectedService,
        detectedAt,
        correlationId,
        sourceEventId);
  }

  /**
   * Transitions this incident to {@code newStatus}, enforcing the allowed-transition rules.
   *
   * @throws IllegalIncidentTransitionException if the transition is not permitted
   */
  public void transitionTo(IncidentStatus newStatus, String reason) {
    IncidentTransitions.requireAllowed(this.status, newStatus);
    this.status = newStatus;
    if (newStatus == IncidentStatus.RESOLVED) {
      this.resolvedAt = Instant.now();
    }
  }

  /** Assigns (or, with {@code null}, unassigns) this incident. Independent of lifecycle status. */
  public void assignTo(String assigneeId) {
    this.assigneeId = assigneeId;
  }

  @PrePersist
  void onCreate() {
    Instant now = Instant.now();
    this.createdAt = now;
    this.updatedAt = now;
  }

  @PreUpdate
  void onUpdate() {
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getIncidentNumber() {
    return incidentNumber;
  }

  public String getTitle() {
    return title;
  }

  public String getDescription() {
    return description;
  }

  public IncidentSeverity getSeverity() {
    return severity;
  }

  public IncidentStatus getStatus() {
    return status;
  }

  public String getSource() {
    return source;
  }

  public String getAffectedService() {
    return affectedService;
  }

  public Instant getDetectedAt() {
    return detectedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Instant getResolvedAt() {
    return resolvedAt;
  }

  public String getAssigneeId() {
    return assigneeId;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public String getSourceEventId() {
    return sourceEventId;
  }

  public long getVersion() {
    return version;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Incident other)) {
      return false;
    }
    return Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
