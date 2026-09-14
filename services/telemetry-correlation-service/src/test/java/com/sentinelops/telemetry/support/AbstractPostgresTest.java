package com.sentinelops.telemetry.support;

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
 * <p>Mirrors the incident service's own {@code AbstractPostgresTest}. The {@code telemetry} schema
 * this service owns does not exist in a real environment's Postgres volume until the {@code
 * postgres-schema-init} Compose service runs (see {@code
 * infrastructure/docker/postgres/provisioning/telemetry-schema-init.sh}) — for this fresh,
 * ephemeral test container, that same idempotent script is simply reused as a {@code
 * docker-entrypoint-initdb.d} script, since there is no pre-existing-volume concern here.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractPostgresTest {

  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
  private static final String APP_USER = "sentinelops_app_test";
  private static final String APP_PASSWORD = "sentinelops-app-test-password";
  private static final Path PROVISIONING_DIR =
      Paths.get("../../infrastructure/docker/postgres/provisioning").toAbsolutePath().normalize();
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
              MountableFile.forHostPath(PHASE2_INIT_DIR.resolve("02-app-user.sh")),
              "/docker-entrypoint-initdb.d/00-app-user.sh")
          .withCopyFileToContainer(
              MountableFile.forHostPath(PROVISIONING_DIR.resolve("telemetry-schema-init.sh")),
              "/docker-entrypoint-initdb.d/01-telemetry-schema.sh");

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    // Migrations run as the schema owner in this test setup — see the note above on why the
    // provisioning script (which grants ownership to POSTGRES_APP_USER) is reused directly.
    registry.add("spring.datasource.username", () -> APP_USER);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
  }
}
