package com.sentinelops.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a previously-consumed inbound event's source event ID so redelivery under Kafka's
 * at-least-once semantics does not cause duplicate effects (deployments, dependency changes, or
 * correlation runs). See ADR 0008 in the incident service for the rationale this service reuses.
 */
@Entity
@Table(name = "processed_events", schema = "telemetry")
public class ProcessedEvent {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(
      name = "source_event_id",
      nullable = false,
      updatable = false,
      unique = true,
      length = 128)
  private String sourceEventId;

  @Column(name = "topic", nullable = false, updatable = false, length = 150)
  private String topic;

  @Column(name = "processed_at", nullable = false, updatable = false)
  private Instant processedAt;

  protected ProcessedEvent() {
    // required by JPA
  }

  private ProcessedEvent(UUID id, String sourceEventId, String topic, Instant processedAt) {
    this.id = id;
    this.sourceEventId = sourceEventId;
    this.topic = topic;
    this.processedAt = processedAt;
  }

  public static ProcessedEvent record(String sourceEventId, String topic) {
    return new ProcessedEvent(UUID.randomUUID(), sourceEventId, topic, Instant.now());
  }

  public UUID getId() {
    return id;
  }

  public String getSourceEventId() {
    return sourceEventId;
  }

  public String getTopic() {
    return topic;
  }

  public Instant getProcessedAt() {
    return processedAt;
  }
}
