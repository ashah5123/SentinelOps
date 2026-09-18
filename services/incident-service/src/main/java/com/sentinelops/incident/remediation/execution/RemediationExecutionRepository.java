package com.sentinelops.incident.remediation.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.remediation.policy.PolicyOutcome;
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
public class RemediationExecutionRepository {

  private static final String SELECT_SQL =
      "SELECT id, runbook_id, incident_id, proposal_id, idempotency_key, status, dry_run, "
          + "requested_by, correlation_id, parameters, blast_radius, policy_decision, "
          + "policy_reason, policy_version, required_approvals, cancel_requested, "
          + "emergency_stop, health_before, health_after, rollback_reason, failure_reason, "
          + "created_at, scheduled_at, started_at, completed_at, rolled_back_at "
          + "FROM incidents.remediation_executions";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public RemediationExecutionRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /** Returns empty if {@code idempotencyKey} already exists — a retried proposal request. */
  public Optional<UUID> tryInsert(RemediationExecutionRow row) {
    try {
      jdbcTemplate.update(
          "INSERT INTO incidents.remediation_executions (id, runbook_id, incident_id, "
              + "proposal_id, idempotency_key, status, dry_run, requested_by, correlation_id, "
              + "parameters, blast_radius, policy_decision, policy_reason, policy_version, "
              + "required_approvals, created_at) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?)",
          row.id(),
          row.runbookId(),
          row.incidentId(),
          row.proposalId(),
          row.idempotencyKey(),
          row.status().name(),
          row.dryRun(),
          row.requestedBy(),
          row.correlationId(),
          writeJson(row.parameters()),
          writeJson(row.blastRadius()),
          row.policyDecision().name(),
          row.policyReason(),
          row.policyVersion(),
          row.requiredApprovals(),
          Timestamp.from(row.createdAt()));
      return Optional.of(row.id());
    } catch (DataIntegrityViolationException e) {
      return Optional.empty();
    }
  }

  public Optional<RemediationExecutionRow> findById(UUID id) {
    return jdbcTemplate.query(SELECT_SQL + " WHERE id = ?", rowMapper(), id).stream().findFirst();
  }

  public Optional<RemediationExecutionRow> findByIdForUpdate(UUID id) {
    return jdbcTemplate.query(SELECT_SQL + " WHERE id = ? FOR UPDATE", rowMapper(), id).stream()
        .findFirst();
  }

  public Optional<RemediationExecutionRow> findByIdempotencyKey(String idempotencyKey) {
    return jdbcTemplate
        .query(SELECT_SQL + " WHERE idempotency_key = ?", rowMapper(), idempotencyKey)
        .stream()
        .findFirst();
  }

  public List<RemediationExecutionRow> findAllOrderedByCreatedAtDesc(int limit) {
    return jdbcTemplate.query(SELECT_SQL + " ORDER BY created_at DESC LIMIT ?", rowMapper(), limit);
  }

  public List<RemediationExecutionRow> findByIncidentId(UUID incidentId) {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE incident_id = ? ORDER BY created_at DESC", rowMapper(), incidentId);
  }

  /** Atomically claims SCHEDULED rows for the poller — never double-picked by a concurrent poll. */
  public List<RemediationExecutionRow> claimDueBatch(int limit) {
    return jdbcTemplate.query(
        SELECT_SQL
            + " WHERE status = 'SCHEDULED' ORDER BY scheduled_at ASC LIMIT ? FOR UPDATE SKIP LOCKED",
        rowMapper(),
        limit);
  }

  /**
   * Counts FAILED/ROLLED_BACK executions of this runbook within the lookback window (for the policy
   * engine's circuit-breaker rule).
   */
  public int countRecentFailures(UUID runbookId, Instant since) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM incidents.remediation_executions WHERE runbook_id = ? "
                + "AND status IN ('FAILED', 'ROLLED_BACK') AND created_at >= ?",
            Integer.class,
            runbookId,
            Timestamp.from(since));
    return count == null ? 0 : count;
  }

  public boolean transitionStatus(
      UUID id, RemediationExecutionStatus expected, RemediationExecutionStatus next, Instant now) {
    String timestampColumn =
        switch (next) {
          case SCHEDULED -> "scheduled_at";
          case RUNNING -> "started_at";
          case SUCCEEDED, FAILED, DENIED, CANCELLED -> "completed_at";
          case ROLLED_BACK -> "rolled_back_at";
          default -> null;
        };
    String sql =
        timestampColumn == null
            ? "UPDATE incidents.remediation_executions SET status = ? WHERE id = ? AND status = ?"
            : "UPDATE incidents.remediation_executions SET status = ?, "
                + timestampColumn
                + " = ? WHERE id = ? AND status = ?";
    int updated =
        timestampColumn == null
            ? jdbcTemplate.update(sql, next.name(), id, expected.name())
            : jdbcTemplate.update(sql, next.name(), Timestamp.from(now), id, expected.name());
    return updated > 0;
  }

  public void requestCancel(UUID id) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_executions SET cancel_requested = true WHERE id = ?", id);
  }

  public void setEmergencyStop(UUID id) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_executions SET emergency_stop = true WHERE id = ?", id);
  }

  public void recordHealthBefore(UUID id, Map<String, Object> health) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_executions SET health_before = ?::jsonb WHERE id = ?",
        writeJson(health),
        id);
  }

  public void recordHealthAfter(UUID id, Map<String, Object> health) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_executions SET health_after = ?::jsonb WHERE id = ?",
        writeJson(health),
        id);
  }

  public void recordFailureReason(UUID id, String reason) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_executions SET failure_reason = ? WHERE id = ?", reason, id);
  }

  public void recordRollbackReason(UUID id, String reason) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_executions SET rollback_reason = ? WHERE id = ?", reason, id);
  }

  @SuppressWarnings("unchecked")
  private RowMapper<RemediationExecutionRow> rowMapper() {
    return (rs, rowNum) -> {
      try {
        return new RemediationExecutionRow(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("runbook_id"),
            (UUID) rs.getObject("incident_id"),
            (UUID) rs.getObject("proposal_id"),
            rs.getString("idempotency_key"),
            RemediationExecutionStatus.valueOf(rs.getString("status")),
            rs.getBoolean("dry_run"),
            rs.getString("requested_by"),
            rs.getString("correlation_id"),
            objectMapper.readValue(rs.getString("parameters"), Map.class),
            objectMapper.readValue(rs.getString("blast_radius"), Map.class),
            PolicyOutcome.valueOf(rs.getString("policy_decision")),
            rs.getString("policy_reason"),
            rs.getInt("policy_version"),
            rs.getInt("required_approvals"),
            rs.getBoolean("cancel_requested"),
            rs.getBoolean("emergency_stop"),
            rs.getString("health_before") == null
                ? null
                : objectMapper.readValue(rs.getString("health_before"), Map.class),
            rs.getString("health_after") == null
                ? null
                : objectMapper.readValue(rs.getString("health_after"), Map.class),
            rs.getString("rollback_reason"),
            rs.getString("failure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            toInstant(rs.getTimestamp("scheduled_at")),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at")),
            toInstant(rs.getTimestamp("rolled_back_at")));
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException("Failed to deserialize remediation_executions row", e);
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
      throw new IllegalStateException("Failed to serialize remediation execution payload", e);
    }
  }
}
