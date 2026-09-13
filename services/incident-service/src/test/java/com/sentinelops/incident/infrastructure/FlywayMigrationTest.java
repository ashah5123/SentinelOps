package com.sentinelops.incident.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.support.AbstractPostgresTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the Flyway migrations under {@code src/main/resources/db/migration} actually apply
 * cleanly against a real PostgreSQL instance, and that the expected tables, constraints, and
 * indexes exist afterward.
 */
class FlywayMigrationTest extends AbstractPostgresTest {

  @Autowired private DataSource dataSource;

  private JdbcTemplate jdbcTemplate() {
    return new JdbcTemplate(dataSource);
  }

  @Test
  void allExpectedTablesExist() {
    for (String table :
        new String[] {
          "incidents.incidents",
          "incidents.incident_status_history",
          "incidents.incident_evidence",
          "incidents.outbox_events",
          "incidents.processed_events",
          "incidents.idempotent_requests",
          "audit.audit_events"
        }) {
      String[] parts = table.split("\\.");
      Integer count =
          jdbcTemplate()
              .queryForObject(
                  "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?",
                  Integer.class,
                  parts[0],
                  parts[1]);
      assertThat(count).as("table %s should exist", table).isEqualTo(1);
    }
  }

  @Test
  void incidentNumberUniqueConstraintExists() {
    Integer count =
        jdbcTemplate()
            .queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                    + "WHERE table_schema = 'incidents' AND table_name = 'incidents' "
                    + "AND constraint_name = 'uq_incidents_incident_number'",
                Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void sourceEventIdPartialUniqueIndexExists() {
    Integer count =
        jdbcTemplate()
            .queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'incidents' "
                    + "AND tablename = 'incidents' AND indexname = 'uq_incidents_source_event_id'",
                Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void expectedFilterIndexesExist() {
    for (String index :
        new String[] {
          "idx_incidents_status",
          "idx_incidents_severity",
          "idx_incidents_affected_service",
          "idx_incidents_detected_at"
        }) {
      Integer count =
          jdbcTemplate()
              .queryForObject(
                  "SELECT count(*) FROM pg_indexes WHERE schemaname = 'incidents' "
                      + "AND tablename = 'incidents' AND indexname = ?",
                  Integer.class,
                  index);
      assertThat(count).as("index %s should exist", index).isEqualTo(1);
    }
  }

  @Test
  void outboxPublishQueuePartialIndexExists() {
    Integer count =
        jdbcTemplate()
            .queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE schemaname = 'incidents' "
                    + "AND tablename = 'outbox_events' AND indexname = 'idx_outbox_events_publish_queue'",
                Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void flywaySchemaHistoryRecordsAllMigrationsAsSuccessful() {
    Integer failedCount =
        jdbcTemplate()
            .queryForObject(
                "SELECT count(*) FROM incidents.flyway_schema_history WHERE success = false",
                Integer.class);
    assertThat(failedCount).isZero();
  }
}
