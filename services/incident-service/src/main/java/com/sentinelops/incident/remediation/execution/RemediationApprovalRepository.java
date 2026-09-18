package com.sentinelops.incident.remediation.execution;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Every approval recorded against an execution. Required-approval counts are always enforced by
 * counting distinct approver rows here, never by trusting a client-supplied count (section: "Build
 * the remediation engine").
 */
@Repository
public class RemediationApprovalRepository {

  private final JdbcTemplate jdbcTemplate;

  public RemediationApprovalRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Returns false if this approver already approved this execution (idempotent, no duplicate
   * credit).
   */
  public boolean tryRecordApproval(
      UUID executionId, String approver, Instant approvedAt, String note) {
    try {
      jdbcTemplate.update(
          "INSERT INTO incidents.remediation_approvals (id, execution_id, approver, approved_at, note) "
              + "VALUES (?, ?, ?, ?, ?)",
          UUID.randomUUID(),
          executionId,
          approver,
          Timestamp.from(approvedAt),
          note);
      return true;
    } catch (DataIntegrityViolationException e) {
      return false;
    }
  }

  public int countDistinctApprovers(UUID executionId) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM incidents.remediation_approvals WHERE execution_id = ?",
            Integer.class,
            executionId);
    return count == null ? 0 : count;
  }

  public boolean hasApproved(UUID executionId, String approver) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM incidents.remediation_approvals WHERE execution_id = ? AND approver = ?",
            Integer.class,
            executionId,
            approver);
    return count != null && count > 0;
  }
}
