package com.sentinelops.incident.ai;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Plain-JDBC persistence for {@link AiSuggestion} (JSONB columns have no first-class JPA mapping in
 * this project — see {@code PgVectorRunbookRepository} for the same pattern applied to {@code
 * vector} columns).
 */
@Repository
public class AiSuggestionRepository {

  private final JdbcTemplate jdbcTemplate;

  public AiSuggestionRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public AiSuggestion insert(AiSuggestion suggestion) {
    jdbcTemplate.update(
        "INSERT INTO incidents.ai_suggestions (id, incident_id, requested_by, correlation_id, "
            + "provider_name, model_name, prompt_template_version, retrieval_config, status, "
            + "structured_result, suggested_severity, suggested_category, failure_reason, "
            + "created_at, review_status) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?)",
        suggestion.id(),
        suggestion.incidentId(),
        suggestion.requestedBy(),
        suggestion.correlationId(),
        suggestion.providerName(),
        suggestion.modelName(),
        suggestion.promptTemplateVersion(),
        suggestion.retrievalConfigJson(),
        suggestion.status().name(),
        suggestion.structuredResultJson(),
        suggestion.suggestedSeverity(),
        suggestion.suggestedCategory(),
        suggestion.failureReason(),
        Timestamp.from(suggestion.createdAt()),
        suggestion.reviewStatus().name());
    return suggestion;
  }

  public Optional<AiSuggestion> findById(UUID id) {
    return jdbcTemplate.query(SELECT_SQL + " WHERE id = ?", rowMapper(), id).stream().findFirst();
  }

  public List<AiSuggestion> findByIncidentId(UUID incidentId) {
    return jdbcTemplate.query(
        SELECT_SQL + " WHERE incident_id = ? ORDER BY created_at DESC", rowMapper(), incidentId);
  }

  /**
   * Records a human review decision. Only these fields are ever updated after creation — the
   * suggestion's own generated content (structured_result, etc.) is never rewritten.
   */
  public void recordReview(
      UUID id,
      AiSuggestion.ReviewStatus reviewStatus,
      String reviewedBy,
      Instant reviewedAt,
      String acceptedFieldsJson,
      String reviewFeedback) {
    jdbcTemplate.update(
        "UPDATE incidents.ai_suggestions SET review_status = ?, reviewed_by = ?, reviewed_at = ?, "
            + "accepted_fields = ?::jsonb, review_feedback = ? WHERE id = ?",
        reviewStatus.name(),
        reviewedBy,
        Timestamp.from(reviewedAt),
        acceptedFieldsJson,
        reviewFeedback,
        id);
  }

  private static final String SELECT_SQL =
      "SELECT id, incident_id, requested_by, correlation_id, provider_name, model_name, "
          + "prompt_template_version, retrieval_config, status, structured_result, "
          + "suggested_severity, suggested_category, failure_reason, created_at, review_status, "
          + "reviewed_by, reviewed_at, accepted_fields, review_feedback "
          + "FROM incidents.ai_suggestions";

  private RowMapper<AiSuggestion> rowMapper() {
    return (rs, rowNum) ->
        new AiSuggestion(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("incident_id"),
            rs.getString("requested_by"),
            rs.getString("correlation_id"),
            rs.getString("provider_name"),
            rs.getString("model_name"),
            rs.getString("prompt_template_version"),
            rs.getString("retrieval_config"),
            AiSuggestion.Status.valueOf(rs.getString("status")),
            rs.getString("structured_result"),
            rs.getString("suggested_severity"),
            rs.getString("suggested_category"),
            rs.getString("failure_reason"),
            rs.getTimestamp("created_at").toInstant(),
            AiSuggestion.ReviewStatus.valueOf(rs.getString("review_status")),
            rs.getString("reviewed_by"),
            rs.getTimestamp("reviewed_at") == null
                ? null
                : rs.getTimestamp("reviewed_at").toInstant(),
            rs.getString("accepted_fields"),
            rs.getString("review_feedback"));
  }
}
