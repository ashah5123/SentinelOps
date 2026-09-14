package com.sentinelops.telemetry.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.telemetry.support.AbstractPostgresTest;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the Flyway migrations under {@code src/main/resources/db/migration} actually apply
 * cleanly against a real PostgreSQL instance owned by the {@code telemetry} schema's application
 * role, and that the expected tables and constraints exist afterward.
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
          "telemetry.ingestion_checkpoints",
          "telemetry.deployments",
          "telemetry.evidence",
          "telemetry.service_dependencies",
          "telemetry.service_dependency_history",
          "telemetry.correlation_results",
          "telemetry.correlation_evidence",
          "telemetry.processed_events",
          "telemetry.outbox_events"
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
  void evidenceFingerprintUniqueConstraintExists() {
    Integer count =
        jdbcTemplate()
            .queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                    + "WHERE table_schema = 'telemetry' AND table_name = 'evidence' "
                    + "AND constraint_name = 'uq_evidence_fingerprint'",
                Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void serviceCannotDependOnItselfCheckConstraintExists() {
    Integer count =
        jdbcTemplate()
            .queryForObject(
                "SELECT count(*) FROM information_schema.table_constraints "
                    + "WHERE table_schema = 'telemetry' AND table_name = 'service_dependencies' "
                    + "AND constraint_name = 'chk_service_dependencies_not_self'",
                Integer.class);
    assertThat(count).isEqualTo(1);
  }

  @Test
  void expectedEvidenceIndexesExist() {
    for (String index :
        new String[] {
          "idx_evidence_type",
          "idx_evidence_source_service",
          "idx_evidence_observed_at",
          "idx_evidence_trace_id",
          "idx_evidence_correlation_id",
          "idx_evidence_deployment_id"
        }) {
      Integer count =
          jdbcTemplate()
              .queryForObject(
                  "SELECT count(*) FROM pg_indexes WHERE schemaname = 'telemetry' "
                      + "AND tablename = 'evidence' AND indexname = ?",
                  Integer.class,
                  index);
      assertThat(count).as("index %s should exist", index).isEqualTo(1);
    }
  }

  @Test
  void flywaySchemaHistoryRecordsAllMigrationsAsSuccessful() {
    Integer failedCount =
        jdbcTemplate()
            .queryForObject(
                "SELECT count(*) FROM telemetry.flyway_schema_history WHERE success = false",
                Integer.class);
    assertThat(failedCount).isZero();
  }
}
