package com.sentinelops.incident.remediation.adapters;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Local demonstration-scope worker-queue pause/resume state — a real deployment would swap this for
 * the actual queue broker's admin API.
 */
@Repository
public class SimulatedQueueRepository {

  private final JdbcTemplate jdbcTemplate;

  public SimulatedQueueRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void setPaused(String name, boolean paused, String updatedBy) {
    Instant now = Instant.now();
    int updated =
        jdbcTemplate.update(
            "UPDATE incidents.simulated_queues SET paused = ?, updated_at = ?, updated_by = ? WHERE name = ?",
            paused,
            Timestamp.from(now),
            updatedBy,
            name);
    if (updated == 0) {
      jdbcTemplate.update(
          "INSERT INTO incidents.simulated_queues (name, paused, updated_at, updated_by) VALUES (?, ?, ?, ?)",
          name,
          paused,
          Timestamp.from(now),
          updatedBy);
    }
  }

  public boolean isPaused(String name) {
    return jdbcTemplate
        .query(
            "SELECT paused FROM incidents.simulated_queues WHERE name = ?",
            (rs, n) -> rs.getBoolean("paused"),
            name)
        .stream()
        .findFirst()
        .orElse(false);
  }
}
