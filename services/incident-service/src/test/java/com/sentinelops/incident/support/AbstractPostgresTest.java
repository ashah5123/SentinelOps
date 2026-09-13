package com.sentinelops.incident.support;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for repository/migration tests: boots the full Spring context (so Flyway actually runs the
 * real migrations from {@code src/main/resources/db/migration}) against a real PostgreSQL instance,
 * with no web server and no Kafka dependency.
 *
 * <p>This service's Flyway migrations assume the {@code incidents} and {@code audit} schemas, the
 * {@code vector} extension, and the non-superuser application role already exist — in the real
 * environment those are created by Phase 2's Postgres container initialization scripts (see {@code
 * infrastructure/docker/postgres/init/}). To keep this test a faithful representation of that
 * environment rather than a divergent fixture, it reuses those exact scripts as the test
 * container's own {@code docker-entrypoint-initdb.d} scripts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractPostgresTest {

  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
  private static final String APP_USER = "sentinelops_app_test";
  private static final String APP_PASSWORD = "sentinelops-app-test-password";
  private static final Path PHASE2_INIT_DIR =
      Paths.get("../../infrastructure/docker/postgres/init").toAbsolutePath().normalize();

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(POSTGRES_IMAGE)
          .withDatabaseName("sentinelops_test")
          .withUsername("sentinelops_test")
          .withPassword("sentinelops_test")
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

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    // Migrations run as the Phase 2 bootstrap superuser, matching the real environment, where
    // Flyway (run by an operator/CI job) has elevated privileges the running application does
    // not.
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
