package com.sentinelops.incident.alerts.escalation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * One row per (incident, routing rule) — the {@code uq_escalations_incident_rule} constraint makes
 * {@link #schedule} naturally idempotent: re-evaluating routing for the same incident never
 * schedules a second escalation for the same rule (section 12).
 */
@Repository
public class EscalationRepository {

  private final JdbcTemplate jdbcTemplate;

  public EscalationRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public boolean schedule(
      UUID id,
      UUID incidentId,
      String routingRuleId,
      int routingRuleVersion,
      Instant scheduledAt,
      Instant now) {
    try {
      jdbcTemplate.update(
          "INSERT INTO alerts.escalations (id, incident_id, routing_rule_id, routing_rule_version, "
              + "scheduled_at, status, created_at) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?)",
          id,
          incidentId,
          routingRuleId,
          routingRuleVersion,
          Timestamp.from(scheduledAt),
          Timestamp.from(now));
      return true;
    } catch (DataIntegrityViolationException e) {
      return false;
    }
  }

  /**
   * {@code SELECT ... FOR UPDATE SKIP LOCKED} so concurrent scheduler instances never deliver the
   * same escalation twice.
   */
  public List<EscalationRow> claimDueBatch(int batchSize, Instant now) {
    return jdbcTemplate.query(
        SELECT_SQL
            + " WHERE status = 'SCHEDULED' AND scheduled_at <= ? "
            + "ORDER BY scheduled_at ASC LIMIT ? FOR UPDATE SKIP LOCKED",
        rowMapper(),
        Timestamp.from(now),
        batchSize);
  }

  public void markDelivered(UUID id, Instant deliveredAt) {
    jdbcTemplate.update(
        "UPDATE alerts.escalations SET status = 'DELIVERED', delivered_at = ? WHERE id = ?",
        Timestamp.from(deliveredAt),
        id);
  }

  public void markCancelled(UUID id, String reason, Instant now) {
    jdbcTemplate.update(
        "UPDATE alerts.escalations SET status = 'CANCELLED', cancelled_at = ?, cancelled_reason = ? WHERE id = ?",
        Timestamp.from(now),
        reason,
        id);
  }

  /**
   * Cancels every still-SCHEDULED escalation for an incident (section 12: acknowledged/resolved
   * incidents cancel ineligible escalations). Returns how many were cancelled.
   */
  public int cancelScheduledForIncident(UUID incidentId, String reason, Instant now) {
    return jdbcTemplate.update(
        "UPDATE alerts.escalations SET status = 'CANCELLED', cancelled_at = ?, cancelled_reason = ? "
            + "WHERE incident_id = ? AND status = 'SCHEDULED'",
        Timestamp.from(now),
        reason,
        incidentId);
  }

  public List<EscalationRow> findByIncidentId(UUID incidentId) {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE incident_id = ? ORDER BY scheduled_at DESC", rowMapper(), incidentId);
  }

  private static final String SELECT_SQL =
      "SELECT id, incident_id, routing_rule_id, routing_rule_version, scheduled_at, status, "
          + "delivered_at, cancelled_at, cancelled_reason, created_at FROM alerts.escalations";

  private RowMapper<EscalationRow> rowMapper() {
    return (rs, rowNum) ->
        new EscalationRow(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("incident_id"),
            rs.getString("routing_rule_id"),
            rs.getInt("routing_rule_version"),
            rs.getTimestamp("scheduled_at").toInstant(),
            rs.getString("status"),
            rs.getTimestamp("delivered_at") == null
                ? null
                : rs.getTimestamp("delivered_at").toInstant(),
            rs.getTimestamp("cancelled_at") == null
                ? null
                : rs.getTimestamp("cancelled_at").toInstant(),
            rs.getString("cancelled_reason"),
            rs.getTimestamp("created_at").toInstant());
  }
}
