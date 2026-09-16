package com.sentinelops.incident.alerts.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public NotificationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Returns {@code true} if a new row was enqueued, {@code false} if this (idempotencyKey, channel)
   * pair already existed.
   */
  public boolean enqueue(
      UUID id,
      UUID incidentId,
      String channel,
      String routingRuleId,
      int routingRuleVersion,
      String idempotencyKey,
      NotificationPayload payload,
      Instant now) {
    try {
      jdbcTemplate.update(
          "INSERT INTO alerts.notifications (id, incident_id, channel, routing_rule_id, "
              + "routing_rule_version, idempotency_key, status, attempt_count, payload, created_at, "
              + "next_attempt_at) "
              + "VALUES (?, ?, ?, ?, ?, ?, 'PENDING', 0, ?::jsonb, ?, ?)",
          id,
          incidentId,
          channel,
          routingRuleId,
          routingRuleVersion,
          idempotencyKey,
          writeJson(payload),
          Timestamp.from(now),
          Timestamp.from(now));
      return true;
    } catch (DataIntegrityViolationException e) {
      return false;
    }
  }

  /**
   * {@code SELECT ... FOR UPDATE SKIP LOCKED} — see {@code OutboxEventRepository#claimBatch} for
   * the same pattern.
   */
  public List<NotificationRow> claimDueBatch(int batchSize, Instant now) {
    return jdbcTemplate.query(
        SELECT_SQL
            + " WHERE status IN ('PENDING', 'FAILED') AND next_attempt_at <= ? "
            + "ORDER BY next_attempt_at ASC LIMIT ? FOR UPDATE SKIP LOCKED",
        rowMapper(),
        Timestamp.from(now),
        batchSize);
  }

  /**
   * Leases claimed rows by pushing next_attempt_at forward, so a slow send is never re-claimed by a
   * concurrent poll.
   */
  public void lease(List<UUID> ids, Instant leaseUntil) {
    for (UUID id : ids) {
      jdbcTemplate.update(
          "UPDATE alerts.notifications SET next_attempt_at = ? WHERE id = ?",
          Timestamp.from(leaseUntil),
          id);
    }
  }

  public void markSent(UUID id, Instant sentAt) {
    jdbcTemplate.update(
        "UPDATE alerts.notifications SET status = 'SENT', sent_at = ?, attempt_count = attempt_count + 1 WHERE id = ?",
        Timestamp.from(sentAt),
        id);
  }

  /** Returns {@code true} if this failure exhausted the retry budget (row is now DEAD_LETTERED). */
  public boolean recordFailure(
      UUID id, String sanitizedError, Instant nextAttemptAt, int maxAttempts) {
    NotificationRow current =
        jdbcTemplate.query(SELECT_SQL + " WHERE id = ?", rowMapper(), id).stream()
            .findFirst()
            .orElseThrow();
    int newAttemptCount = current.attemptCount() + 1;
    boolean deadLettered = newAttemptCount >= maxAttempts;
    jdbcTemplate.update(
        "UPDATE alerts.notifications SET status = ?, attempt_count = ?, last_error = ?, "
            + "next_attempt_at = ? WHERE id = ?",
        deadLettered ? "DEAD_LETTERED" : "FAILED",
        newAttemptCount,
        truncate(sanitizedError, 500),
        Timestamp.from(nextAttemptAt),
        id);
    return deadLettered;
  }

  /**
   * Immediately dead-letters a notification whose failure is known non-retryable (e.g. a 4xx from a
   * webhook sink) — never wastes the retry budget on a failure retrying can't fix.
   */
  public void deadLetter(UUID id, String sanitizedError) {
    jdbcTemplate.update(
        "UPDATE alerts.notifications SET status = 'DEAD_LETTERED', attempt_count = attempt_count + 1, "
            + "last_error = ? WHERE id = ?",
        truncate(sanitizedError, 500),
        id);
  }

  public List<NotificationRow> findByIncidentId(UUID incidentId) {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE incident_id = ? ORDER BY created_at DESC", rowMapper(), incidentId);
  }

  public java.util.Optional<Instant> findLastSentAt() {
    return jdbcTemplate
        .query(
            "SELECT max(sent_at) AS last_sent_at FROM alerts.notifications WHERE status = 'SENT'",
            (rs, rowNum) -> {
              java.sql.Timestamp ts = rs.getTimestamp("last_sent_at");
              return ts == null ? null : ts.toInstant();
            })
        .stream()
        .findFirst()
        .filter(java.util.Objects::nonNull);
  }

  public long countDeadLettered() {
    Long count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM alerts.notifications WHERE status = 'DEAD_LETTERED'", Long.class);
    return count == null ? 0 : count;
  }

  /**
   * Administrator-only manual replay (section 11) — resets a dead-lettered notification back to
   * PENDING.
   */
  public boolean replay(UUID id, Instant now) {
    int updated =
        jdbcTemplate.update(
            "UPDATE alerts.notifications SET status = 'PENDING', next_attempt_at = ? "
                + "WHERE id = ? AND status = 'DEAD_LETTERED'",
            Timestamp.from(now),
            id);
    return updated > 0;
  }

  private static final String SELECT_SQL =
      "SELECT id, incident_id, channel, routing_rule_id, routing_rule_version, idempotency_key, "
          + "status, attempt_count, last_error, payload, created_at, sent_at, next_attempt_at "
          + "FROM alerts.notifications";

  private RowMapper<NotificationRow> rowMapper() {
    return (rs, rowNum) ->
        new NotificationRow(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("incident_id"),
            rs.getString("channel"),
            rs.getString("routing_rule_id"),
            rs.getInt("routing_rule_version"),
            rs.getString("idempotency_key"),
            rs.getString("status"),
            rs.getInt("attempt_count"),
            rs.getString("last_error"),
            rs.getString("payload"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
            rs.getTimestamp("next_attempt_at").toInstant());
  }

  private String writeJson(NotificationPayload payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize notification payload", e);
    }
  }

  private String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
