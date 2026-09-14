package com.sentinelops.incident.support;

import com.sentinelops.incident.events.EventTypes;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for full integration and API tests: boots the complete Spring context on a random port
 * against real PostgreSQL and Kafka-compatible (Confluent Kafka image, exercised purely through the
 * standard Kafka client protocol — the same protocol Redpanda serves in every other environment)
 * Testcontainers instances.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
public abstract class AbstractIntegrationTest {

  private static final DockerImageName POSTGRES_IMAGE =
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");
  private static final Path PHASE2_INIT_DIR =
      Paths.get("../../infrastructure/docker/postgres/init").toAbsolutePath().normalize();

  @Container
  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(POSTGRES_IMAGE)
          .withDatabaseName("sentinelops_test")
          .withUsername("sentinelops_test")
          .withPassword("sentinelops_test")
          .withEnv("POSTGRES_APP_USER", "sentinelops_app_test")
          .withEnv("POSTGRES_APP_PASSWORD", "sentinelops-app-test-password")
          .withCopyFileToContainer(
              MountableFile.forHostPath(PHASE2_INIT_DIR.resolve("01-extensions.sql")),
              "/docker-entrypoint-initdb.d/01-extensions.sql")
          .withCopyFileToContainer(
              MountableFile.forHostPath(PHASE2_INIT_DIR.resolve("02-app-user.sh")),
              "/docker-entrypoint-initdb.d/02-app-user.sh")
          .withCopyFileToContainer(
              MountableFile.forHostPath(PHASE2_INIT_DIR.resolve("03-schemas.sh")),
              "/docker-entrypoint-initdb.d/03-schemas.sh");

  @Container
  protected static final ConfluentKafkaContainer KAFKA =
      new ConfluentKafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

  @Autowired protected TestRestTemplate restTemplate;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
  }

  @BeforeAll
  static void createTopics() throws ExecutionException, InterruptedException {
    try (AdminClient adminClient =
        AdminClient.create(Map.of("bootstrap.servers", KAFKA.getBootstrapServers()))) {
      List<NewTopic> topics =
          List.of(
              new NewTopic(EventTypes.TELEMETRY_ANOMALY_V1, 1, (short) 1),
              new NewTopic(EventTypes.INCIDENT_DETECTED_V1, 1, (short) 1),
              new NewTopic(EventTypes.AUDIT_EVENT_V1, 1, (short) 1),
              new NewTopic(EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1, 1, (short) 1),
              new NewTopic(EventTypes.TELEMETRY_ANOMALY_V1_DLQ, 1, (short) 1),
              new NewTopic(EventTypes.INCIDENT_DETECTED_V1_DLQ, 1, (short) 1),
              new NewTopic(EventTypes.INCIDENT_EVIDENCE_CORRELATED_V1_DLQ, 1, (short) 1));
      adminClient.createTopics(topics).all().get();
    }
  }

  protected static Duration awaitTimeout() {
    return Duration.ofSeconds(15);
  }
}
