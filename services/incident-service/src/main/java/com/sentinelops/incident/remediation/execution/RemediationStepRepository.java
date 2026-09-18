package com.sentinelops.incident.remediation.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.remediation.adapters.RemediationActionType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Retries update the same row's {@code attempt_count}/{@code status} rather than inserting a new
 * row — "how many times did step 2 retry" is a single row to read, not a join+count.
 */
@Repository
public class RemediationStepRepository {

  private static final String SELECT_SQL =
      "SELECT id, execution_id, step_index, step_name, adapter_type, parameters, "
          + "is_rollback_step, status, attempt_count, started_at, completed_at, output, error "
          + "FROM incidents.remediation_steps";

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public RemediationStepRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public UUID insertPending(
      UUID executionId,
      int stepIndex,
      String stepName,
      RemediationActionType adapterType,
      Map<String, Object> parameters,
      boolean rollbackStep) {
    UUID id = UUID.randomUUID();
    jdbcTemplate.update(
        "INSERT INTO incidents.remediation_steps (id, execution_id, step_index, step_name, "
            + "adapter_type, parameters, is_rollback_step, status, attempt_count) "
            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0)",
        id,
        executionId,
        stepIndex,
        stepName,
        adapterType.name(),
        writeJson(parameters),
        rollbackStep);
    return id;
  }

  public List<RemediationStepRow> findByExecutionId(UUID executionId) {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE execution_id = ? ORDER BY is_rollback_step ASC, step_index ASC",
        rowMapper(),
        executionId);
  }

  public void markRunning(UUID id, Instant startedAt) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_steps SET status = 'RUNNING', attempt_count = attempt_count + 1, "
            + "started_at = COALESCE(started_at, ?) WHERE id = ?",
        Timestamp.from(startedAt),
        id);
  }

  public void markSucceeded(UUID id, Instant completedAt, Map<String, Object> output) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_steps SET status = 'SUCCEEDED', completed_at = ?, output = ?::jsonb WHERE id = ?",
        Timestamp.from(completedAt),
        writeJson(output),
        id);
  }

  public void markFailed(UUID id, Instant completedAt, String error) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_steps SET status = 'FAILED', completed_at = ?, error = ? WHERE id = ?",
        Timestamp.from(completedAt),
        error,
        id);
  }

  public void markSkipped(UUID id) {
    jdbcTemplate.update(
        "UPDATE incidents.remediation_steps SET status = 'SKIPPED' WHERE id = ?", id);
  }

  @SuppressWarnings("unchecked")
  private RowMapper<RemediationStepRow> rowMapper() {
    return (rs, rowNum) -> {
      try {
        return new RemediationStepRow(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("execution_id"),
            rs.getInt("step_index"),
            rs.getString("step_name"),
            RemediationActionType.valueOf(rs.getString("adapter_type")),
            objectMapper.readValue(rs.getString("parameters"), Map.class),
            rs.getBoolean("is_rollback_step"),
            RemediationStepStatus.valueOf(rs.getString("status")),
            rs.getInt("attempt_count"),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at")),
            rs.getString("output") == null
                ? null
                : objectMapper.readValue(rs.getString("output"), Map.class),
            rs.getString("error"));
      } catch (Exception e) {
        throw new IllegalStateException("Failed to deserialize remediation_steps row", e);
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
      throw new IllegalStateException("Failed to serialize remediation step payload", e);
    }
  }
}
