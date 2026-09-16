package com.sentinelops.incident.alerts.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import com.sentinelops.incident.alerts.correlation.IncidentAlertContext;
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

/**
 * Plain-JDBC persistence for {@code alerts.alert_events} (JSONB label/annotation columns have no
 * first-class JPA mapping in this project — see {@code AiSuggestionRepository}, Phase 11, for the
 * same pattern).
 */
@Repository
public class AlertEventRepository {

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public AlertEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * Attempts to insert a new alert event. Returns {@code true} if this was a new row (a genuinely
   * new delivery) or {@code false} if {@code dedupKey} already existed (a retried delivery of the
   * exact same alert instance) — see the {@code uq_alert_events_dedup_key} constraint. Never throws
   * for the duplicate case; the caller decides what a duplicate delivery means.
   */
  public boolean tryInsert(
      UUID id,
      CanonicalAlert alert,
      String fingerprint,
      int fingerprintVersion,
      Instant ingestedAt,
      String rawPayloadHash,
      String dedupKey,
      String correlationId) {
    try {
      jdbcTemplate.update(
          "INSERT INTO alerts.alert_events (id, connector_type, source, external_id, fingerprint, "
              + "fingerprint_version, status, alert_name, summary, description, severity, service, "
              + "environment, region, labels, annotations, source_timestamp, ingested_at, "
              + "generator_url, schema_version, raw_payload_hash, dedup_key, correlation_id, created_at) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)",
          id,
          alert.connectorType(),
          alert.source(),
          alert.externalId(),
          fingerprint,
          fingerprintVersion,
          alert.status().name(),
          alert.alertName(),
          alert.summary(),
          alert.description(),
          alert.severity(),
          alert.service(),
          alert.environment(),
          alert.region(),
          writeJson(alert.labels()),
          writeJson(alert.annotations()),
          Timestamp.from(alert.sourceTimestamp()),
          Timestamp.from(ingestedAt),
          alert.generatorUrl(),
          alert.schemaVersion(),
          rawPayloadHash,
          dedupKey,
          correlationId,
          Timestamp.from(ingestedAt));
      return true;
    } catch (DataIntegrityViolationException e) {
      return false;
    }
  }

  public Optional<AlertEventRow> findByDedupKey(String dedupKey) {
    return jdbcTemplate.query(SELECT_SQL + " WHERE dedup_key = ?", rowMapper(), dedupKey).stream()
        .findFirst();
  }

  public Optional<AlertEventRow> findById(UUID id) {
    return jdbcTemplate.query(SELECT_SQL + " WHERE id = ?", rowMapper(), id).stream().findFirst();
  }

  public Optional<Instant> findLastIngestedAt(String connectorType) {
    return jdbcTemplate
        .query(
            "SELECT max(ingested_at) AS last_ingested_at FROM alerts.alert_events WHERE connector_type = ?",
            (rs, rowNum) -> {
              java.sql.Timestamp ts = rs.getTimestamp("last_ingested_at");
              return ts == null ? null : ts.toInstant();
            },
            connectorType)
        .stream()
        .findFirst()
        .filter(java.util.Objects::nonNull);
  }

  public List<AlertEventRow> findByIncidentId(UUID incidentId) {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE incident_id = ? ORDER BY ingested_at DESC", rowMapper(), incidentId);
  }

  public void attachIncident(UUID alertEventId, UUID incidentId) {
    jdbcTemplate.update(
        "UPDATE alerts.alert_events SET incident_id = ? WHERE id = ?", incidentId, alertEventId);
  }

  /**
   * The most recent alert's service/environment/region/source for each of the given incidents — see
   * {@code CorrelationEngine}.
   */
  public List<IncidentAlertContext> findLatestContextByIncidentIds(List<UUID> incidentIds) {
    if (incidentIds.isEmpty()) {
      return List.of();
    }
    String placeholders = String.join(",", incidentIds.stream().map(id -> "?").toList());
    return jdbcTemplate.query(
        "SELECT DISTINCT ON (incident_id) incident_id, service, environment, region, source "
            + "FROM alerts.alert_events WHERE incident_id IN ("
            + placeholders
            + ") "
            + "ORDER BY incident_id, ingested_at DESC",
        (rs, rowNum) ->
            new IncidentAlertContext(
                (UUID) rs.getObject("incident_id"),
                rs.getString("service"),
                rs.getString("environment"),
                rs.getString("region"),
                rs.getString("source")),
        incidentIds.toArray());
  }

  private static final String SELECT_SQL =
      "SELECT id, connector_type, source, external_id, fingerprint, fingerprint_version, status, "
          + "alert_name, summary, description, severity, service, environment, region, labels, "
          + "annotations, source_timestamp, ingested_at, generator_url, schema_version, "
          + "raw_payload_hash, dedup_key, correlation_id, incident_id, created_at "
          + "FROM alerts.alert_events";

  private RowMapper<AlertEventRow> rowMapper() {
    return (rs, rowNum) ->
        new AlertEventRow(
            (UUID) rs.getObject("id"),
            rs.getString("connector_type"),
            rs.getString("source"),
            rs.getString("external_id"),
            rs.getString("fingerprint"),
            rs.getInt("fingerprint_version"),
            rs.getString("status"),
            rs.getString("alert_name"),
            rs.getString("summary"),
            rs.getString("description"),
            rs.getString("severity"),
            rs.getString("service"),
            rs.getString("environment"),
            rs.getString("region"),
            readJson(rs.getString("labels")),
            readJson(rs.getString("annotations")),
            rs.getTimestamp("source_timestamp").toInstant(),
            rs.getTimestamp("ingested_at").toInstant(),
            rs.getString("generator_url"),
            rs.getInt("schema_version"),
            rs.getString("raw_payload_hash"),
            rs.getString("dedup_key"),
            rs.getString("correlation_id"),
            (UUID) rs.getObject("incident_id"),
            rs.getTimestamp("created_at").toInstant());
  }

  private String writeJson(Map<String, String> value) {
    try {
      return objectMapper.writeValueAsString(value == null ? Map.of() : value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize alert labels/annotations", e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> readJson(String json) {
    if (json == null) {
      return Map.of();
    }
    try {
      return objectMapper.readValue(json, Map.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to deserialize alert labels/annotations", e);
    }
  }
}
