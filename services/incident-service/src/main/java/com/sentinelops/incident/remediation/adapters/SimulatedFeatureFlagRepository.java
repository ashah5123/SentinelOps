package com.sentinelops.incident.remediation.adapters;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Local demonstration-scope feature-flag store — a real deployment would swap this for the actual
 * flag service, keeping the same adapter contract.
 */
@Repository
public class SimulatedFeatureFlagRepository {

  private final JdbcTemplate jdbcTemplate;

  public SimulatedFeatureFlagRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public Optional<Boolean> isEnabled(String name) {
    return jdbcTemplate
        .query(
            "SELECT enabled FROM incidents.simulated_feature_flags WHERE name = ?",
            (rs, n) -> rs.getBoolean("enabled"),
            name)
        .stream()
        .findFirst();
  }

  public void setEnabled(String name, boolean enabled, String updatedBy) {
    Instant now = Instant.now();
    int updated =
        jdbcTemplate.update(
            "UPDATE incidents.simulated_feature_flags SET enabled = ?, updated_at = ?, updated_by = ? WHERE name = ?",
            enabled,
            Timestamp.from(now),
            updatedBy,
            name);
    if (updated == 0) {
      jdbcTemplate.update(
          "INSERT INTO incidents.simulated_feature_flags (name, enabled, updated_at, updated_by) VALUES (?, ?, ?, ?)",
          name,
          enabled,
          Timestamp.from(now),
          updatedBy);
    }
  }
}
