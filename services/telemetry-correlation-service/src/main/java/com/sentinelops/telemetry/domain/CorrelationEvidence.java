package com.sentinelops.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One piece of evidence selected by a {@link CorrelationResult}, with its score and a
 * human-readable explanation of every rule that contributed to that score — so a reviewer can see
 * exactly why this evidence was surfaced, not just that it was.
 */
@Entity
@Table(
    name = "correlation_evidence",
    schema = "telemetry",
    uniqueConstraints =
        @jakarta.persistence.UniqueConstraint(
            columnNames = {"correlation_result_id", "evidence_id"}))
public class CorrelationEvidence {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "correlation_result_id", nullable = false, updatable = false)
  private UUID correlationResultId;

  @Column(name = "evidence_id", nullable = false, updatable = false)
  private UUID evidenceId;

  @Column(name = "score", nullable = false, updatable = false)
  private double score;

  @Column(name = "explanation", nullable = false, updatable = false, length = 1000)
  private String explanation;

  @Column(name = "rank", nullable = false, updatable = false)
  private int rank;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected CorrelationEvidence() {
    // required by JPA
  }

  public CorrelationEvidence(
      UUID correlationResultId, UUID evidenceId, double score, String explanation, int rank) {
    this.id = UUID.randomUUID();
    this.correlationResultId = correlationResultId;
    this.evidenceId = evidenceId;
    this.score = score;
    this.explanation = explanation;
    this.rank = rank;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public UUID getCorrelationResultId() {
    return correlationResultId;
  }

  public UUID getEvidenceId() {
    return evidenceId;
  }

  public double getScore() {
    return score;
  }

  public String getExplanation() {
    return explanation;
  }

  public int getRank() {
    return rank;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
