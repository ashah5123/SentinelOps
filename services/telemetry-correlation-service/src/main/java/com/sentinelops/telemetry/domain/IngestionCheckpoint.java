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
 * The ingestion watermark for one (source, monitored service) pair. {@link #watermark} is the UTC
 * instant through which telemetry has been successfully normalized and persisted; the next polling
 * cycle queries from {@code watermark - overlapWindow} (see ingestion properties) through now, so
 * late-arriving telemetry near the previous boundary is re-queried but never missed. Deduplication
 * (by {@link Evidence#getFingerprint()}) makes re-querying that overlap safe.
 *
 * <p>The watermark only advances after a polling cycle's evidence has been durably persisted in the
 * same transaction — a failed cycle leaves the watermark untouched so the next cycle retries the
 * same window.
 */
@Entity
@Table(
    name = "ingestion_checkpoints",
    schema = "telemetry",
    uniqueConstraints =
        @jakarta.persistence.UniqueConstraint(columnNames = {"source", "monitored_service"}))
public class IngestionCheckpoint {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "source", nullable = false, updatable = false, length = 20)
  private SourceSystem source;

  @Column(name = "monitored_service", nullable = false, updatable = false, length = 150)
  private String monitoredService;

  @Column(name = "watermark", nullable = false)
  private Instant watermark;

  @Column(name = "last_run_at")
  private Instant lastRunAt;

  @Column(name = "last_run_status", length = 20)
  private String lastRunStatus;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected IngestionCheckpoint() {
    // required by JPA
  }

  public IngestionCheckpoint(
      SourceSystem source, String monitoredService, Instant initialWatermark) {
    this.id = UUID.randomUUID();
    this.source = source;
    this.monitoredService = monitoredService;
    this.watermark = initialWatermark;
    this.updatedAt = Instant.now();
  }

  public void advance(Instant newWatermark) {
    this.watermark = newWatermark;
    this.lastRunAt = Instant.now();
    this.lastRunStatus = "SUCCESS";
    this.updatedAt = this.lastRunAt;
  }

  public void recordFailure() {
    this.lastRunAt = Instant.now();
    this.lastRunStatus = "FAILED";
    this.updatedAt = this.lastRunAt;
  }

  public UUID getId() {
    return id;
  }

  public SourceSystem getSource() {
    return source;
  }

  public String getMonitoredService() {
    return monitoredService;
  }

  public Instant getWatermark() {
    return watermark;
  }

  public Instant getLastRunAt() {
    return lastRunAt;
  }

  public String getLastRunStatus() {
    return lastRunStatus;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
