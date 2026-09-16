package com.sentinelops.incident.support;

import com.sentinelops.incident.events.EventTypes;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * Base for full integration and API tests: boots the complete Spring context on a random port
 * against real PostgreSQL, a Kafka-compatible broker (Confluent Kafka image, exercised purely
 * through the standard Kafka client protocol — the same protocol Redpanda serves in every other
 * environment), and a real local Keycloak instance (Phase 7) — access tokens used by these tests
 * are genuine, signature-valid tokens issued by that instance, never fabricated or mocked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureMockMvc
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

  @Container
  protected static final GenericContainer<?> KEYCLOAK = KeycloakTestSupport.newContainer();

  private static final Map<String, String> TOKEN_CACHE = new ConcurrentHashMap<>();

  @Autowired protected TestRestTemplate restTemplate;
  @Autowired protected MockMvc mockMvc;

  @DynamicPropertySource
  static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () ->
            KeycloakTestSupport.issuerUri(KEYCLOAK, "sentinelops")
                + "/protocol/openid-connect/certs");
    registry.add(
        "sentinelops.security.issuer",
        () -> KeycloakTestSupport.issuerUri(KEYCLOAK, "sentinelops"));
  }

  /** A real, signature-valid access token for one of the three demo users in realm-export.json. */
  protected static String tokenFor(String role) {
    return TOKEN_CACHE.computeIfAbsent(
        role,
        r -> {
          String username =
              switch (r) {
                case "VIEWER" -> "viewer-demo";
                case "RESPONDER" -> "responder-demo";
                case "ADMIN" -> "admin-demo";
                default -> throw new IllegalArgumentException("Unknown demo role: " + r);
              };
          String password = username + "-local-only";
          return KeycloakTestSupport.fetchAccessToken(
              KEYCLOAK, "sentinelops", "sentinelops-api", username, password);
        });
  }

  protected static HttpHeaders bearerHeaders(String role) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(tokenFor(role));
    return headers;
  }

  protected static <T> HttpEntity<T> withAuth(T body, String role) {
    return withAuth(body, role, new HttpHeaders());
  }

  protected static <T> HttpEntity<T> withAuth(T body, String role, HttpHeaders extraHeaders) {
    HttpHeaders headers = bearerHeaders(role);
    headers.addAll(extraHeaders);
    return new HttpEntity<>(body, headers);
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
