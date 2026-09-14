package com.sentinelops.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * The current state of one directed service dependency edge (source depends on target). Removed
 * edges are deleted from this table but remain visible in {@link ServiceDependencyHistory}. A
 * dependency cycle (A -> B -> A) is a valid runtime topology — e.g. two services that call each
 * other over different endpoints — and is not rejected; only a service depending on itself is
 * invalid (see {@code DependencyEventListener}).
 */
@Entity
@Table(name = "service_dependencies", schema = "telemetry")
public class ServiceDependency {

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

  @Column(name = "effective_at", nullable = false)
  private Instant effectiveAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ServiceDependency() {
    // required by JPA
  }

  public ServiceDependency(
      String sourceService,
      String targetService,
      String dependencyType,
      String environment,
      Instant effectiveAt) {
    this.id = UUID.randomUUID();
    this.sourceService = sourceService;
    this.targetService = targetService;
    this.dependencyType = dependencyType;
    this.environment = environment;
    this.effectiveAt = effectiveAt;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public void touch(Instant effectiveAt) {
    this.effectiveAt = effectiveAt;
    this.updatedAt = Instant.now();
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

  public Instant getEffectiveAt() {
    return effectiveAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
