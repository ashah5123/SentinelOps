package com.sentinelops.incident.alerts.correlation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AlertCorrelationRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AlertCorrelationRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public void record(UUID alertEventId, CorrelationDecision decision) {
    if (!decision.matched()) {
      throw new IllegalArgumentException("Only a matched correlation decision is persisted");
    }
    jdbcTemplate.update(
        "INSERT INTO alerts.alert_correlations (id, alert_event_id, incident_id, rule_id, "
            + "rule_version, matched_fields, explanation, correlated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
        UUID.randomUUID(),
        alertEventId,
        decision.incidentId(),
        decision.ruleId(),
        decision.ruleVersion(),
        writeJson(decision.matchedFields()),
        decision.explanation(),
        Timestamp.from(Instant.now()));
  }

  public List<AlertCorrelationRow> findByIncidentId(UUID incidentId) {
    return jdbcTemplate.query(
        "SELECT id, alert_event_id, incident_id, rule_id, rule_version, matched_fields, "
            + "explanation, correlated_at FROM alerts.alert_correlations WHERE incident_id = ? "
            + "ORDER BY correlated_at DESC",
        (rs, rowNum) ->
            new AlertCorrelationRow(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("alert_event_id"),
                (UUID) rs.getObject("incident_id"),
                rs.getString("rule_id"),
                rs.getInt("rule_version"),
                rs.getString("matched_fields"),
                rs.getString("explanation"),
                rs.getTimestamp("correlated_at").toInstant()),
        incidentId);
  }

  private String writeJson(Map<String, String> value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize correlation matched fields", e);
    }
  }

  public record AlertCorrelationRow(
      UUID id,
      UUID alertEventId,
      UUID incidentId,
      String ruleId,
      int ruleVersion,
      String matchedFieldsJson,
      String explanation,
      Instant correlatedAt) {}
}
