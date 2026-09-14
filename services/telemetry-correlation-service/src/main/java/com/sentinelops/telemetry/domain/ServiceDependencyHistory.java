package com.sentinelops.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only history of every {@code service.dependency.changed.v1} event applied to the
 * dependency graph, keyed by the source event's ID for idempotent consumption.
 */
@Entity
@Table(name = "service_dependency_history", schema = "telemetry")
public class ServiceDependencyHistory {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "source_service", nullable = false, updatable = false, length = 150)
  private String sourceService;

  @Column(name = "target_service", nullable = false, updatable = false, length = 150)
  private String targetService;

  @Column(name = "dependency_type", nullable = false, updatable = false, length = 50)
  private String dependencyType;

  @Column(name = "environment", nullable = false, updatable = false, length = 50)
  private String environment;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation", nullable = false, updatable = false, length = 20)
  private DependencyOperation operation;

  @Column(name = "effective_at", nullable = false, updatable = false)
  private Instant effectiveAt;

  @Column(
      name = "source_event_id",
      nullable = false,
      updatable = false,
      unique = true,
      length = 128)
  private String sourceEventId;

  @Column(name = "recorded_at", nullable = false, updatable = false)
  private Instant recordedAt;

  protected ServiceDependencyHistory() {
    // required by JPA
  }

  public ServiceDependencyHistory(
      String sourceService,
      String targetService,
      String dependencyType,
      String environment,
      DependencyOperation operation,
      Instant effectiveAt,
      String sourceEventId) {
    this.id = UUID.randomUUID();
    this.sourceService = sourceService;
    this.targetService = targetService;
    this.dependencyType = dependencyType;
    this.environment = environment;
    this.operation = operation;
    this.effectiveAt = effectiveAt;
    this.sourceEventId = sourceEventId;
    this.recordedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getSourceService() {
    return sourceService;
  }

  public String getTargetService() {
    return targetService;
  }

  public String getDependencyType() {
    return dependencyType;
  }

  public String getEnvironment() {
    return environment;
  }

  public DependencyOperation getOperation() {
    return operation;
  }

  public Instant getEffectiveAt() {
    return effectiveAt;
  }

  public String getSourceEventId() {
    return sourceEventId;
  }

  public Instant getRecordedAt() {
    return recordedAt;
  }
}
