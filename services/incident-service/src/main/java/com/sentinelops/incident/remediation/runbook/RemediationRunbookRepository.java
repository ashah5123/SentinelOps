package com.sentinelops.incident.remediation.runbook;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * A (slug, version) row is immutable once created (section: "Build the remediation engine") — a
 * runbook change always registers a new version rather than updating a row in place, so a
 * scheduled/running execution's captured runbook_id can never change shape underneath it.
 */
@Repository
public class RemediationRunbookRepository {

  private static final String SELECT_SQL =
      "SELECT id, slug, version, title, risk_classification, definition_yaml, definition_hash, "
          + "step_count, is_active, created_at, created_by FROM incidents.remediation_runbooks";

  private final JdbcTemplate jdbcTemplate;

  public RemediationRunbookRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** Returns empty if this exact (slug, version) already exists — idempotent registration. */
  public Optional<UUID> tryInsert(RemediationRunbookRow row) {
    try {
      jdbcTemplate.update(
          "INSERT INTO incidents.remediation_runbooks (id, slug, version, title, "
              + "risk_classification, definition_yaml, definition_hash, step_count, is_active, "
              + "created_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
          row.id(),
          row.slug(),
          row.version(),
          row.title(),
          row.riskClassification().name(),
          row.definitionYaml(),
          row.definitionHash(),
          row.stepCount(),
          row.active(),
          Timestamp.from(row.createdAt()),
          row.createdBy());
      return Optional.of(row.id());
    } catch (DataIntegrityViolationException e) {
      return Optional.empty();
    }
  }

  public Optional<RemediationRunbookRow> findById(UUID id) {
    return jdbcTemplate.query(SELECT_SQL + " WHERE id = ?", rowMapper(), id).stream().findFirst();
  }

  public Optional<RemediationRunbookRow> findBySlugAndVersion(String slug, int version) {
    return jdbcTemplate
        .query(SELECT_SQL + " WHERE slug = ? AND version = ?", rowMapper(), slug, version)
        .stream()
        .findFirst();
  }

  /**
   * The current active version of a slug, if any — the one new executions should propose against.
   */
  public Optional<RemediationRunbookRow> findActiveBySlug(String slug) {
    return jdbcTemplate
        .query(
            SELECT_SQL + " WHERE slug = ? AND is_active = true ORDER BY version DESC LIMIT 1",
            rowMapper(),
            slug)
        .stream()
        .findFirst();
  }

  public List<RemediationRunbookRow> findAllActive() {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE is_active = true ORDER BY slug ASC", rowMapper());
  }

  public int deactivateOtherVersions(String slug, int keepVersion) {
    return jdbcTemplate.update(
        "UPDATE incidents.remediation_runbooks SET is_active = false "
            + "WHERE slug = ? AND version != ? AND is_active = true",
        slug,
        keepVersion);
  }

  private RowMapper<RemediationRunbookRow> rowMapper() {
    return (rs, rowNum) ->
        new RemediationRunbookRow(
            (UUID) rs.getObject("id"),
            rs.getString("slug"),
            rs.getInt("version"),
            rs.getString("title"),
            RiskClassification.valueOf(rs.getString("risk_classification")),
            rs.getString("definition_yaml"),
            rs.getString("definition_hash"),
            rs.getInt("step_count"),
            rs.getBoolean("is_active"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("created_by"));
  }
}
