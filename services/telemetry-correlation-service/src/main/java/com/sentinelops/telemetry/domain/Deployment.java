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
 * A single deployment event for a monitored service, consumed idempotently from {@code
 * deployment.changed.v1}. See {@code docs/events/telemetry-correlation-events.md}.
 */
@Entity
@Table(name = "deployments", schema = "telemetry")
public class Deployment {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "deployment_id", nullable = false, updatable = false, length = 128)
  private String deploymentId;

  @Column(name = "service_name", nullable = false, updatable = false, length = 150)
  private String serviceName;

  @Column(name = "version", nullable = false, updatable = false, length = 128)
  private String version;

  @Column(name = "environment", nullable = false, updatable = false, length = 50)
  private String environment;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, updatable = false, length = 20)
  private DeploymentStatus status;

  @Column(name = "started_at", nullable = false, updatable = false)
  private Instant startedAt;

  @Column(name = "completed_at", updatable = false)
  private Instant completedAt;

  @Column(name = "source", nullable = false, updatable = false, length = 100)
  private String source;

  @Column(name = "rollback_of_deployment_id", updatable = false, length = 128)
  private String rollbackOfDeploymentId;

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

  protected Deployment() {
    // required by JPA
  }

  private Deployment(
      String deploymentId,
      String serviceName,
      String version,
      String environment,
      DeploymentStatus status,
      Instant startedAt,
      Instant completedAt,
      String source,
      String rollbackOfDeploymentId,
      String sourceEventId,
      String correlationId) {
    this.id = UUID.randomUUID();
    this.deploymentId = deploymentId;
    this.serviceName = serviceName;
    this.version = version;
    this.environment = environment;
    this.status = status;
    this.startedAt = startedAt;
    this.completedAt = completedAt;
    this.source = source;
    this.rollbackOfDeploymentId = rollbackOfDeploymentId;
    this.sourceEventId = sourceEventId;
    this.correlationId = correlationId;
    this.createdAt = Instant.now();
  }

  public static Deployment record(
      String deploymentId,
      String serviceName,
      String version,
      String environment,
      DeploymentStatus status,
      Instant startedAt,
      Instant completedAt,
      String source,
      String rollbackOfDeploymentId,
      String sourceEventId,
      String correlationId) {
    return new Deployment(
        deploymentId,
        serviceName,
        version,
        environment,
        status,
        startedAt,
        completedAt,
        source,
        rollbackOfDeploymentId,
        sourceEventId,
        correlationId);
  }

  public UUID getId() {
    return id;
  }

  public String getDeploymentId() {
    return deploymentId;
  }

  public String getServiceName() {
    return serviceName;
  }

  public String getVersion() {
    return version;
  }

  public String getEnvironment() {
    return environment;
  }

  public DeploymentStatus getStatus() {
    return status;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public String getSource() {
    return source;
  }

  public String getRollbackOfDeploymentId() {
    return rollbackOfDeploymentId;
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
