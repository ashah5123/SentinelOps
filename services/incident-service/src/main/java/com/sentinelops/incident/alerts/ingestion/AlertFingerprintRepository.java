package com.sentinelops.incident.alerts.ingestion;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Semantic-dedup / occurrence state for a fingerprint (section 6/7). {@link #lockForUpdate(String)}
 * takes a row-level {@code SELECT ... FOR UPDATE} lock so two concurrent ingestions of the same
 * fingerprint can never both decide "this is the first occurrence" and both create an incident —
 * the second transaction blocks until the first commits, then observes the now-updated row. This
 * mirrors {@code OutboxEventRepository#claimBatch}'s use of row locking for the same class of
 * concurrency problem.
 */
@Repository
public class AlertFingerprintRepository {

  private final JdbcTemplate jdbcTemplate;

  public AlertFingerprintRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * Inserts a fresh row if none exists yet for this fingerprint; a no-op otherwise. Must run before
   * {@link #lockForUpdate}.
   */
  public void ensureExists(String fingerprint, int fingerprintVersion, String source, Instant now) {
    jdbcTemplate.update(
        "INSERT INTO alerts.alert_fingerprints (fingerprint, fingerprint_version, source, "
            + "first_seen_at, last_seen_at, occurrence_count, status, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, 0, 'ACTIVE', ?) "
            + "ON CONFLICT (fingerprint) DO NOTHING",
        fingerprint,
        fingerprintVersion,
        source,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  /**
   * Row-locks the fingerprint for the remainder of the caller's transaction. Call {@link
   * #ensureExists} first.
   */
  public FingerprintRow lockForUpdate(String fingerprint) {
    List<FingerprintRow> rows =
        jdbcTemplate.query(
            SELECT_SQL + " WHERE fingerprint = ? FOR UPDATE", rowMapper(), fingerprint);
    return rows.stream()
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Fingerprint row missing after ensureExists: " + fingerprint));
  }

  public Optional<FingerprintRow> find(String fingerprint) {
    return jdbcTemplate
        .query(SELECT_SQL + " WHERE fingerprint = ?", rowMapper(), fingerprint)
        .stream()
        .findFirst();
  }

  /**
   * Records a new occurrence attached to (or continuing) {@code activeIncidentId}, resetting the
   * counter to 1 for a fresh incident lifecycle.
   */
  public void startNewOccurrence(String fingerprint, UUID activeIncidentId, Instant now) {
    jdbcTemplate.update(
        "UPDATE alerts.alert_fingerprints SET occurrence_count = 1, last_seen_at = ?, "
            + "active_incident_id = ?, status = 'ACTIVE', updated_at = ? WHERE fingerprint = ?",
        Timestamp.from(now),
        activeIncidentId,
        Timestamp.from(now),
        fingerprint);
  }

  /**
   * Increments the occurrence counter for a repeated firing alert attached to the same still-open
   * incident.
   */
  public void incrementOccurrence(String fingerprint, Instant now) {
    jdbcTemplate.update(
        "UPDATE alerts.alert_fingerprints SET occurrence_count = occurrence_count + 1, "
            + "last_seen_at = ?, updated_at = ? WHERE fingerprint = ?",
        Timestamp.from(now),
        Timestamp.from(now),
        fingerprint);
  }

  /**
   * Marks the fingerprint resolved (section 9) without touching occurrence_count or
   * active_incident_id.
   */
  public void markResolved(String fingerprint, Instant now) {
    jdbcTemplate.update(
        "UPDATE alerts.alert_fingerprints SET status = 'RESOLVED', last_seen_at = ?, updated_at = ? "
            + "WHERE fingerprint = ?",
        Timestamp.from(now),
        Timestamp.from(now),
        fingerprint);
  }

  private static final String SELECT_SQL =
      "SELECT fingerprint, fingerprint_version, source, first_seen_at, last_seen_at, "
          + "occurrence_count, active_incident_id, status, updated_at FROM alerts.alert_fingerprints";

  private RowMapper<FingerprintRow> rowMapper() {
    return (rs, rowNum) ->
        new FingerprintRow(
            rs.getString("fingerprint"),
            rs.getInt("fingerprint_version"),
            rs.getString("source"),
            rs.getTimestamp("first_seen_at").toInstant(),
            rs.getTimestamp("last_seen_at").toInstant(),
            rs.getInt("occurrence_count"),
            (UUID) rs.getObject("active_incident_id"),
            rs.getString("status"),
            rs.getTimestamp("updated_at").toInstant());
  }
}
