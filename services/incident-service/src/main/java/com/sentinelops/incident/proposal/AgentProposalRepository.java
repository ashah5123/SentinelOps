package com.sentinelops.incident.proposal;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class AgentProposalRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AgentProposalRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Returns empty if {@code idempotencyKey} already exists (a retried proposal request), never
   * throwing.
   */
  public Optional<UUID> tryInsert(AgentProposalRow row) {
    try {
      jdbcTemplate.update(
          "INSERT INTO incidents.agent_proposals (id, incident_id, action_type, parameters, reason, "
              + "evidence_references, expected_version, content_hash, risk_classification, "
              + "requested_by, requested_actor_type, correlation_id, idempotency_key, created_at, "
              + "expires_at, status) "
              + "VALUES (?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
          row.id(),
          row.incidentId(),
          row.actionType().name(),
          writeJson(row.parameters()),
          row.reason(),
          writeJson(row.evidenceReferences()),
          row.expectedVersion(),
          row.contentHash(),
          row.riskClassification().name(),
          row.requestedBy(),
          row.requestedActorType(),
          row.correlationId(),
          row.idempotencyKey(),
          Timestamp.from(row.createdAt()),
          Timestamp.from(row.expiresAt()));
      return Optional.of(row.id());
    } catch (DataIntegrityViolationException e) {
      return Optional.empty();
    }
  }

  public Optional<AgentProposalRow> findById(UUID id) {
    return jdbcTemplate.query(SELECT_SQL + " WHERE id = ?", rowMapper(), id).stream().findFirst();
  }

  public Optional<AgentProposalRow> findByIdForUpdate(UUID id) {
    return jdbcTemplate.query(SELECT_SQL + " WHERE id = ? FOR UPDATE", rowMapper(), id).stream()
        .findFirst();
  }

  public Optional<AgentProposalRow> findByIdempotencyKey(String idempotencyKey) {
    return jdbcTemplate
        .query(SELECT_SQL + " WHERE idempotency_key = ?", rowMapper(), idempotencyKey)
        .stream()
        .findFirst();
  }

  public List<AgentProposalRow> findByIncidentId(UUID incidentId) {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE incident_id = ? ORDER BY created_at DESC", rowMapper(), incidentId);
  }

  public List<AgentProposalRow> findAllOrderedByCreatedAtDesc(int limit) {
    return jdbcTemplate.query(SELECT_SQL + " ORDER BY created_at DESC LIMIT ?", rowMapper(), limit);
  }

  public List<AgentProposalRow> findPendingPastExpiry(Instant now, int limit) {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE status = 'PENDING' AND expires_at < ? LIMIT ? FOR UPDATE SKIP LOCKED",
        rowMapper(),
        Timestamp.from(now),
        limit);
  }

  public void markExpired(UUID id) {
    jdbcTemplate.update(
        "UPDATE incidents.agent_proposals SET status = 'EXPIRED' WHERE id = ? AND status = 'PENDING'",
        id);
  }

  /**
   * Returns {@code true} if this call actually transitioned the row (guards against a
   * double-approve race).
   */
  public boolean approve(
      UUID id,
      String approvedBy,
      Instant approvedAt,
      String reviewNote,
      Instant approvalExpiresAt) {
    int updated =
        jdbcTemplate.update(
            "UPDATE incidents.agent_proposals SET status = 'APPROVED', approved_by = ?, approved_at = ?, "
                + "review_note = ?, approval_expires_at = ? WHERE id = ? AND status = 'PENDING'",
            approvedBy,
            Timestamp.from(approvedAt),
            reviewNote,
            Timestamp.from(approvalExpiresAt),
            id);
    return updated > 0;
  }

  public boolean reject(UUID id, String rejectedBy, Instant rejectedAt, String rejectionNote) {
    int updated =
        jdbcTemplate.update(
            "UPDATE incidents.agent_proposals SET status = 'REJECTED', rejected_by = ?, rejected_at = ?, "
                + "rejection_note = ? WHERE id = ? AND status = 'PENDING'",
            rejectedBy,
            Timestamp.from(rejectedAt),
            rejectionNote,
            id);
    return updated > 0;
  }

  /**
   * Atomically consumes the approval — {@code WHERE approval_consumed = false} means a second,
   * concurrent call (or a replay) always affects zero rows and returns {@code false}. Callers must
   * check the return value before executing the underlying domain action.
   */
  public boolean consumeApproval(UUID id) {
    int updated =
        jdbcTemplate.update(
            "UPDATE incidents.agent_proposals SET approval_consumed = true "
                + "WHERE id = ? AND status = 'APPROVED' AND approval_consumed = false",
            id);
    return updated > 0;
  }

  public void markExecuted(UUID id, Instant executedAt, String executionResult) {
    jdbcTemplate.update(
        "UPDATE incidents.agent_proposals SET status = 'EXECUTED', executed_at = ?, execution_result = ? WHERE id = ?",
        Timestamp.from(executedAt),
        executionResult,
        id);
  }

  public void markExecutionFailed(UUID id, String executionError) {
    jdbcTemplate.update(
        "UPDATE incidents.agent_proposals SET status = 'EXECUTION_FAILED', execution_error = ? WHERE id = ?",
        executionError,
        id);
  }

  private static final String SELECT_SQL =
      "SELECT id, incident_id, action_type, parameters, reason, evidence_references, "
          + "expected_version, content_hash, risk_classification, requested_by, "
          + "requested_actor_type, correlation_id, idempotency_key, created_at, expires_at, "
          + "status, approved_by, approved_at, review_note, approval_expires_at, "
          + "approval_consumed, rejected_by, rejected_at, rejection_note, executed_at, "
          + "execution_result, execution_error FROM incidents.agent_proposals";

  @SuppressWarnings("unchecked")
  private RowMapper<AgentProposalRow> rowMapper() {
    return (rs, rowNum) -> {
      try {
        return new AgentProposalRow(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("incident_id"),
            ProposalActionType.valueOf(rs.getString("action_type")),
            objectMapper.readValue(rs.getString("parameters"), Map.class),
            rs.getString("reason"),
            objectMapper.readValue(rs.getString("evidence_references"), List.class),
            rs.getLong("expected_version"),
            rs.getString("content_hash"),
            RiskClassification.valueOf(rs.getString("risk_classification")),
            rs.getString("requested_by"),
            rs.getString("requested_actor_type"),
            rs.getString("correlation_id"),
            rs.getString("idempotency_key"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant(),
            ProposalStatus.valueOf(rs.getString("status")),
            rs.getString("approved_by"),
            toInstant(rs.getTimestamp("approved_at")),
            rs.getString("review_note"),
            toInstant(rs.getTimestamp("approval_expires_at")),
            rs.getBoolean("approval_consumed"),
            rs.getString("rejected_by"),
            toInstant(rs.getTimestamp("rejected_at")),
            rs.getString("rejection_note"),
            toInstant(rs.getTimestamp("executed_at")),
            rs.getString("execution_result"),
            rs.getString("execution_error"));
      } catch (Exception e) {
        throw new IllegalStateException("Failed to deserialize agent_proposals row", e);
      }
    };
  }

  private Instant toInstant(Timestamp ts) {
    return ts == null ? null : ts.toInstant();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize agent proposal payload", e);
    }
  }
}
