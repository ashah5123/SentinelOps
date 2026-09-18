package com.sentinelops.incident.remediation.lock;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A simple, portable distributed lock keyed by a blast-radius resource string (e.g.
 * "service:checkout-api|environment:production") — the same idempotent-insert idiom used throughout
 * this codebase (e.g. {@code AlertFingerprintRepository}) rather than a new locking primitive
 * (section: "Build the remediation engine" — "concurrency limits and distributed locking to prevent
 * conflicting remediations").
 */
@Repository
public class RemediationLockRepository {

  private final JdbcTemplate jdbcTemplate;

  public RemediationLockRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Returns true if the lock was acquired (or already held, expired, by this same execution). */
  public boolean acquire(String resourceKey, UUID executionId, Instant now, Instant expiresAt) {
    jdbcTemplate.update(
        "DELETE FROM incidents.remediation_locks WHERE resource_key = ? AND expires_at < ?",
        resourceKey,
        Timestamp.from(now));
    try {
      int inserted =
          jdbcTemplate.update(
              "INSERT INTO incidents.remediation_locks (resource_key, execution_id, acquired_at, expires_at) "
                  + "VALUES (?, ?, ?, ?) ON CONFLICT (resource_key) DO NOTHING",
              resourceKey,
              executionId,
              Timestamp.from(now),
              Timestamp.from(expiresAt));
      if (inserted > 0) {
        return true;
      }
    } catch (DataIntegrityViolationException e) {
      return false;
    }
    // Lock row exists — only "acquired" if this same execution already holds it (re-entrant poll).
    Integer heldByUs =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM incidents.remediation_locks WHERE resource_key = ? AND execution_id = ?",
            Integer.class,
            resourceKey,
            executionId);
    return heldByUs != null && heldByUs > 0;
  }

  public void release(String resourceKey, UUID executionId) {
    jdbcTemplate.update(
        "DELETE FROM incidents.remediation_locks WHERE resource_key = ? AND execution_id = ?",
        resourceKey,
        executionId);
  }
}
