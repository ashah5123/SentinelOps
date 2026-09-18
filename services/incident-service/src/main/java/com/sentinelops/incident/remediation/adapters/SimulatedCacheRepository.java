package com.sentinelops.incident.remediation.adapters;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Local demonstration-scope bounded cache-namespace simulation — a real deployment would swap this
 * for Redis/Memcached, keeping the same adapter contract.
 */
@Repository
public class SimulatedCacheRepository {

  private final JdbcTemplate jdbcTemplate;

  public SimulatedCacheRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public int clearNamespace(String namespace) {
    return jdbcTemplate.update(
        "DELETE FROM incidents.simulated_cache_entries WHERE namespace = ?", namespace);
  }

  public int countInNamespace(String namespace) {
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM incidents.simulated_cache_entries WHERE namespace = ?",
            Integer.class,
            namespace);
    return count == null ? 0 : count;
  }
}
