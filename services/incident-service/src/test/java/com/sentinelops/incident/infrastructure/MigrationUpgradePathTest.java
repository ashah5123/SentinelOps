package com.sentinelops.incident.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Release-safety migration test (Phase 9): starts from an empty database, applies migrations up to
 * the second-to-latest version (simulating "the currently deployed schema"), inserts representative
 * data through that schema, then upgrades to the latest version and verifies the previously
 * inserted data is still present and readable — the "upgrade from the current previous schema,
 * preserving existing data" check called for in docs/development/operations.md.
 *
 * <p>Unlike {@link FlywayMigrationTest} (clean-database, full-migration structural checks), this
 * test exercises the specific N-1 -> N upgrade path and reads back through the same non-superuser
 * application role the running service uses, to also confirm that role's grants survive the
 * upgrade.
 */
@Testcontainers
class MigrationUpgradePathTest {

  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
  private static final String APP_USER = "sentinelops_app_migration_test";
  private static final String APP_PASSWORD = "sentinelops-app-migration-test-password";
  private static final Path PHASE2_INIT_DIR =
      Paths.get("../../infrastructure/docker/postgres/init").toAbsolutePath().normalize();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(POSTGRES_IMAGE)
          .withDatabaseName("sentinelops_migration_test")
          .withUsername("sentinelops_migration_test")
          .withPassword("sentinelops_migration_test")
          .withEnv("POSTGRES_APP_USER", APP_USER)
          .withEnv("POSTGRES_APP_PASSWORD", APP_PASSWORD)
          .withCopyFileToContainer(
              MountableFile.forHostPath(PHASE2_INIT_DIR.resolve("01-extensions.sql")),
              "/docker-entrypoint-initdb.d/01-extensions.sql")
          .withCopyFileToContainer(
              MountableFile.forHostPath(PHASE2_INIT_DIR.resolve("02-app-user.sh")),
              "/docker-entrypoint-initdb.d/02-app-user.sh")
          .withCopyFileToContainer(
              MountableFile.forHostPath(PHASE2_INIT_DIR.resolve("03-schemas.sh")),
              "/docker-entrypoint-initdb.d/03-schemas.sh");

  /**
   * The "previous schema" version this test upgrades from — one less than the current latest
   * migration (V8). Update this alongside adding a new highest-numbered migration, so this test
   * keeps exercising "the current previous schema -> current schema" rather than a stale pair.
   */
  private static final String PREVIOUS_SCHEMA_VERSION = "8";

  private Flyway flywayTargetingSchemaVersion(String target) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas("incidents", "audit", "runbooks")
        .defaultSchema("incidents")
        .locations("classpath:db/migration")
        .target(target)
        .load();
  }

  @Test
  void upgradingFromThePreviousSchemaPreservesExistingData() throws Exception {
    // 1. Apply migrations up to the previous schema version only (empty database -> V1..V6).
    Flyway toPrevious = flywayTargetingSchemaVersion(PREVIOUS_SCHEMA_VERSION);
    toPrevious.migrate();
    assertThat(highestAppliedVersion(toPrevious)).isEqualTo(PREVIOUS_SCHEMA_VERSION);

    // 2. Insert representative data through that schema, as the application's own non-superuser
    // role, exactly as IncidentCommandService would (incident + status history + audit event).
    UUID incidentId = UUID.randomUUID();
    Instant now = Instant.now();
    try (Connection appConn = appConnection()) {
      appConn.setAutoCommit(false);
      try (Statement stmt = appConn.createStatement()) {
        stmt.execute(
            "INSERT INTO incidents.incidents (id, incident_number, title, severity, status, "
                + "source, affected_service, detected_at, created_at, updated_at, correlation_id, "
                + "version) VALUES ('"
                + incidentId
                + "', 'INC-2026-MIGTEST', 'Migration upgrade test incident', 'SEV3', 'DETECTED', "
                + "'migration-test', 'migration-test-service', now(), now(), now(), "
                + "'migration-upgrade-test', 0)");
        stmt.execute(
            "INSERT INTO incidents.incident_status_history (id, incident_id, to_status, "
                + "reason, occurred_at, correlation_id) VALUES (gen_random_uuid(), '"
                + incidentId
                + "', 'DETECTED', 'created for migration upgrade test', now(), "
                + "'migration-upgrade-test')");
        stmt.execute(
            "INSERT INTO audit.audit_events (id, incident_id, action, actor_type, actor_id, "
                + "correlation_id, occurred_at) VALUES (gen_random_uuid(), '"
                + incidentId
                + "', 'INCIDENT_CREATED', 'SYSTEM', 'migration-upgrade-test', "
                + "'migration-upgrade-test', now())");
      }
      appConn.commit();
    }

    // 3. Upgrade through the migration path to the current latest version (V9).
    Flyway toLatest =
        flywayTargetingSchemaVersion(org.flywaydb.core.api.MigrationVersion.LATEST.getVersion());
    toLatest.migrate();
    assertThat(toLatest.info().current()).isNotNull();

    // 4. Verify the application (as its own non-superuser role) can still read the data inserted
    // under the previous schema, and can write through the tables the new migration introduced
    // (runbooks.runbook_documents/runbook_chunks, added by V9) without any special-casing.
    try (Connection appConn = appConnection()) {
      try (Statement stmt = appConn.createStatement();
          ResultSet rs =
              stmt.executeQuery(
                  "SELECT title, status FROM incidents.incidents WHERE id = '"
                      + incidentId
                      + "'")) {
        assertThat(rs.next()).as("previously inserted incident is still readable").isTrue();
        assertThat(rs.getString("title")).isEqualTo("Migration upgrade test incident");
        assertThat(rs.getString("status")).isEqualTo("DETECTED");
      }

      try (Statement stmt = appConn.createStatement();
          ResultSet rs =
              stmt.executeQuery(
                  "SELECT count(*) FROM audit.audit_events WHERE incident_id = '"
                      + incidentId
                      + "'")) {
        rs.next();
        assertThat(rs.getInt(1))
            .as("previously inserted audit event is still readable")
            .isEqualTo(1);
      }

      UUID documentId = UUID.randomUUID();
      String dummyVector = zeroVectorWithOneAt(384, 7);
      try (Statement stmt = appConn.createStatement()) {
        stmt.execute(
            "INSERT INTO runbooks.runbook_documents (id, slug, title, version, content_hash, "
                + "source_path, owner, last_reviewed_at, created_at, updated_at) VALUES ('"
                + documentId
                + "', 'migration-upgrade-test-runbook', 'Migration upgrade test runbook', 1, "
                + "'deadbeef', 'docs/runbooks/migration-test.md', 'platform-team', '2026-01-01', "
                + "now(), now())");
        stmt.execute(
            "INSERT INTO runbooks.runbook_chunks (id, document_id, stable_chunk_id, chunk_index, "
                + "heading, content, token_count, embedding, is_active, created_at) VALUES "
                + "(gen_random_uuid(), '"
                + documentId
                + "', 'migration-upgrade-test-chunk-0', 0, 'Symptoms', 'dummy chunk content', 3, '"
                + dummyVector
                + "', true, now())");
      }
      try (Statement stmt = appConn.createStatement();
          ResultSet rs =
              stmt.executeQuery(
                  "SELECT count(*) FROM runbooks.runbook_chunks WHERE document_id = '"
                      + documentId
                      + "' AND is_active = true")) {
        rs.next();
        assertThat(rs.getInt(1))
            .as("the application can write through the tables the new migration introduced")
            .isEqualTo(1);
      }
    }
  }

  /** A 384-dimension pgvector literal (e.g. "[0,0,1,0,...]") with a single 1 at the given index. */
  private String zeroVectorWithOneAt(int dimensions, int oneIndex) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < dimensions; i++) {
      sb.append(i == oneIndex ? "1" : "0");
      if (i < dimensions - 1) {
        sb.append(",");
      }
    }
    return sb.append("]").toString();
  }

  private String highestAppliedVersion(Flyway flyway) {
    MigrationInfo current = flyway.info().current();
    assertThat(current).as("at least one migration should have applied").isNotNull();
    return current.getVersion().getVersion();
  }

  private Connection appConnection() throws Exception {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), APP_USER, APP_PASSWORD);
  }
}
