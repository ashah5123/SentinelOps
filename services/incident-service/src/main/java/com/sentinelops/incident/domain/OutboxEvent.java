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
 * A transactional outbox record.
 *
 * <p>Written in the same database transaction as the domain change it describes, then claimed and
 * published asynchronously by the outbox publisher. See ADR 0007 for the full design rationale,
 * including the concurrency-safe claiming strategy and duplicate-delivery assumptions consumers
 * must handle.
 */
@Entity
@Table(name = "outbox_events", schema = "incidents")
public class OutboxEvent {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "aggregate_type", nullable = false, updatable = false, length = 50)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false, updatable = false)
  private UUID aggregateId;

  @Column(name = "topic", nullable = false, updatable = false, length = 150)
  private String topic;

  @Column(name = "event_type", nullable = false, updatable = false, length = 100)
  private String eventType;

  @Column(name = "schema_version", nullable = false, updatable = false)
  private int schemaVersion;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, updatable = false)
  private String payload;

  @Column(name = "correlation_id", nullable = false, updatable = false, length = 64)
  private String correlationId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "published_at")
  private Instant publishedAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private Instant nextAttemptAt;

  @Column(name = "last_error", length = 1000)
  private String lastError;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private OutboxStatus status;

  protected OutboxEvent() {
    // required by JPA
  }

  private OutboxEvent(
      UUID id,
      String aggregateType,
      UUID aggregateId,
      String topic,
      String eventType,
      int schemaVersion,
      String payload,
      String correlationId,
      Instant createdAt) {
    this.id = id;
    this.aggregateType = aggregateType;
    this.aggregateId = aggregateId;
    this.topic = topic;
    this.eventType = eventType;
    this.schemaVersion = schemaVersion;
    this.payload = payload;
    this.correlationId = correlationId;
    this.createdAt = createdAt;
    this.attemptCount = 0;
    this.nextAttemptAt = createdAt;
    this.status = OutboxStatus.PENDING;
  }

  public static OutboxEvent pending(
      String aggregateType,
      UUID aggregateId,
      String topic,
      String eventType,
      int schemaVersion,
      String payload,
      String correlationId) {
    return new OutboxEvent(
        UUID.randomUUID(),
        aggregateType,
        aggregateId,
        topic,
        eventType,
        schemaVersion,
        payload,
        correlationId,
        Instant.now());
  }

  public void markPublished() {
    this.status = OutboxStatus.PUBLISHED;
    this.publishedAt = Instant.now();
  }

  /**
   * Marks this PENDING row as claimed for publication without changing its attempt count or status,
   * by moving {@code nextAttemptAt} forward to {@code leaseUntil}. This is a short, committed
   * transaction's substitute for holding the {@code SELECT ... FOR UPDATE SKIP LOCKED} row lock
   * (and the surrounding database transaction) open for the duration of the network call to the
   * broker: once the claiming transaction commits, the row lock is released, but no other publisher
   * instance will re-claim the row until the lease expires. If this process crashes before
   * publishing completes, the row simply becomes claimable again once the lease expires and is
   * republished — the same at-least-once, safe-to-reprocess guarantee this outbox has always
   * documented (see {@code OutboxPublisher}).
   */
  public void lease(Instant leaseUntil) {
    this.nextAttemptAt = leaseUntil;
  }

  public void recordFailedAttempt(String error, Instant nextAttemptAt, int maxAttempts) {
    this.attemptCount++;
    this.lastError = error;
    if (this.attemptCount >= maxAttempts) {
      this.status = OutboxStatus.FAILED;
    } else {
      this.nextAttemptAt = nextAttemptAt;
    }
  }

  public UUID getId() {
    return id;
  }

  public String getAggregateType() {
    return aggregateType;
  }

  public UUID getAggregateId() {
    return aggregateId;
  }

  public String getTopic() {
    return topic;
  }

  public String getEventType() {
    return eventType;
  }

  public int getSchemaVersion() {
    return schemaVersion;
  }

  public String getPayload() {
    return payload;
  }

  public String getCorrelationId() {
    return correlationId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getPublishedAt() {
    return publishedAt;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public Instant getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getLastError() {
    return lastError;
  }

  public OutboxStatus getStatus() {
    return status;
  }
}
