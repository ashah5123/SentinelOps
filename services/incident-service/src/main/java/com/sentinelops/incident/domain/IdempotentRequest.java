package com.sentinelops.incident.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Records the outcome of a client request made with an {@code Idempotency-Key} header, so a safe
 * repeat of the same request returns the original response instead of re-executing it.
 *
 * <p>{@code requestHash} lets the service detect and reject reuse of an idempotency key with a
 * materially different request payload.
 */
@Entity
@Table(name = "idempotent_requests", schema = "incidents")
public class IdempotentRequest {

  @Id
  @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
  private String idempotencyKey;

  @Column(name = "request_hash", nullable = false, updatable = false, length = 128)
  private String requestHash;

  @Column(name = "response_status", nullable = false, updatable = false)
  private int responseStatus;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", nullable = false, updatable = false)
  private String responseBody;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected IdempotentRequest() {
    // required by JPA
  }

  private IdempotentRequest(
      String idempotencyKey,
      String requestHash,
      int responseStatus,
      String responseBody,
      Instant createdAt) {
    this.idempotencyKey = idempotencyKey;
    this.requestHash = requestHash;
    this.responseStatus = responseStatus;
    this.responseBody = responseBody;
    this.createdAt = createdAt;
  }

  public static IdempotentRequest record(
      String idempotencyKey, String requestHash, int responseStatus, String responseBody) {
    return new IdempotentRequest(
        idempotencyKey, requestHash, responseStatus, responseBody, Instant.now());
  }

  public String getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public int getResponseStatus() {
    return responseStatus;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
