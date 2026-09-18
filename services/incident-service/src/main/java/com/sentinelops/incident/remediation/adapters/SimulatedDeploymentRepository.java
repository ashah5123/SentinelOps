package com.sentinelops.incident.remediation.adapters;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * A local, in-database simulation of a Kubernetes Deployment's observable state — restart
 * timestamp, replica count, a bounded revision history for rollback, and a coarse health flag.
 * Never a real cluster call: see {@code docs/development/remediation.md}'s "local implementations"
 * section for why (no real cluster is available in this environment) and what a production adapter
 * would need to change (swap this repository for a real Kubernetes client call, keeping the same
 * {@link RemediationActionAdapter} contract).
 */
@Repository
public class SimulatedDeploymentRepository {

  private static final int MAX_REVISION_HISTORY = 5;

  private final JdbcTemplate jdbcTemplate;
  private final ObjectMapper objectMapper;

  public SimulatedDeploymentRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
    this.jdbcTemplate = jdbcTemplate;
    this.objectMapper = objectMapper;
  }

  public SimulatedDeploymentRow ensureExists(String service, String environment) {
    Instant now = Instant.now();
    jdbcTemplate.update(
        "INSERT INTO incidents.simulated_deployments (service, environment, replicas, revision, "
            + "revision_history, healthy, updated_at) VALUES (?, ?, 1, 1, '[]'::jsonb, true, ?) "
            + "ON CONFLICT (service, environment) DO NOTHING",
        service,
        environment,
        Timestamp.from(now));
    return find(service, environment).orElseThrow();
  }

  public Optional<SimulatedDeploymentRow> find(String service, String environment) {
    return jdbcTemplate
        .query(
            "SELECT service, environment, replicas, revision, revision_history, healthy, "
                + "restarted_at, updated_at FROM incidents.simulated_deployments "
                + "WHERE service = ? AND environment = ?",
            (rs, rowNum) ->
                new SimulatedDeploymentRow(
                    rs.getString("service"),
                    rs.getString("environment"),
                    rs.getInt("replicas"),
                    rs.getInt("revision"),
                    readHistory(rs.getString("revision_history")),
                    rs.getBoolean("healthy"),
                    rs.getTimestamp("restarted_at") == null
                        ? null
                        : rs.getTimestamp("restarted_at").toInstant(),
                    rs.getTimestamp("updated_at").toInstant()),
            service,
            environment)
        .stream()
        .findFirst();
  }

  public void restart(String service, String environment) {
    ensureExists(service, environment);
    jdbcTemplate.update(
        "UPDATE incidents.simulated_deployments SET restarted_at = ?, updated_at = ? "
            + "WHERE service = ? AND environment = ?",
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()),
        service,
        environment);
  }

  public void scale(String service, String environment, int newReplicas) {
    SimulatedDeploymentRow current = ensureExists(service, environment);
    List<Map<String, Object>> history = new java.util.ArrayList<>(current.revisionHistory());
    history.add(Map.of("revision", current.revision(), "replicas", current.replicas()));
    if (history.size() > MAX_REVISION_HISTORY) {
      history = history.subList(history.size() - MAX_REVISION_HISTORY, history.size());
    }
    jdbcTemplate.update(
        "UPDATE incidents.simulated_deployments SET replicas = ?, revision = revision + 1, "
            + "revision_history = ?::jsonb, updated_at = ? WHERE service = ? AND environment = ?",
        newReplicas,
        writeJson(history),
        Timestamp.from(Instant.now()),
        service,
        environment);
  }

  /** Returns {@code false} if there is no prior revision to roll back to. */
  public boolean rollbackToPreviousRevision(String service, String environment) {
    SimulatedDeploymentRow current = ensureExists(service, environment);
    if (current.revisionHistory().isEmpty()) {
      return false;
    }
    List<Map<String, Object>> history = new java.util.ArrayList<>(current.revisionHistory());
    Map<String, Object> previous = history.remove(history.size() - 1);
    jdbcTemplate.update(
        "UPDATE incidents.simulated_deployments SET replicas = ?, revision = ?, "
            + "revision_history = ?::jsonb, updated_at = ? WHERE service = ? AND environment = ?",
        ((Number) previous.get("replicas")).intValue(),
        ((Number) previous.get("revision")).intValue(),
        writeJson(history),
        Timestamp.from(Instant.now()),
        service,
        environment);
    return true;
  }

  /**
   * For local demonstration/test fault injection — see docs/development/remediation.md's
   * rollback-demo section.
   */
  public void setHealthy(String service, String environment, boolean healthy) {
    ensureExists(service, environment);
    jdbcTemplate.update(
        "UPDATE incidents.simulated_deployments SET healthy = ?, updated_at = ? WHERE service = ? AND environment = ?",
        healthy,
        Timestamp.from(Instant.now()),
        service,
        environment);
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> readHistory(String json) {
    if (json == null) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, List.class);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to deserialize revision history", e);
    }
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize revision history", e);
    }
  }
}
