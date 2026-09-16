package com.sentinelops.incident.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

/**
 * A real, local Keycloak instance (not a mock) used by integration tests that need to validate this
 * service's OAuth2 resource-server configuration end to end — signature, issuer, audience, and
 * expiry — against actual identity-provider behavior. Both the primary "sentinelops" realm (shared
 * with the local Compose deployment; see infrastructure/docker/keycloak/realm-export.json) and a
 * second, test-only realm (used only to mint a validly-signed token with the wrong issuer) are
 * imported on startup.
 */
public final class KeycloakTestSupport {

  private static final Path KEYCLOAK_DIR =
      Paths.get("../../infrastructure/docker/keycloak").toAbsolutePath().normalize();
  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private KeycloakTestSupport() {}

  public static GenericContainer<?> newContainer() {
    return new GenericContainer<>("quay.io/keycloak/keycloak:26.0.7")
        .withCommand("start-dev", "--import-realm")
        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", "admin-test-only")
        .withEnv("KC_HTTP_ENABLED", "true")
        .withCopyFileToContainer(
            MountableFile.forHostPath(KEYCLOAK_DIR.resolve("realm-export.json")),
            "/opt/keycloak/data/import/realm-export.json")
        .withCopyFileToContainer(
            MountableFile.forHostPath(
                KEYCLOAK_DIR.resolve("test-fixtures/realm-export-other-issuer.json")),
            "/opt/keycloak/data/import/realm-export-other-issuer.json")
        .withExposedPorts(8080)
        .waitingFor(
            Wait.forHttp("/realms/sentinelops/.well-known/openid-configuration").forStatusCode(200))
        .withStartupTimeout(Duration.ofMinutes(3));
  }

  public static String issuerUri(GenericContainer<?> keycloak, String realm) {
    return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/" + realm;
  }

  /**
   * Resource Owner Password Credentials grant against a public client with direct-access-grants
   * enabled. This is a local-only, test/demo shortcut (never used by any production code path in
   * this service) documented as such in docs/development/security.md.
   */
  public static String fetchAccessToken(
      GenericContainer<?> keycloak,
      String realm,
      String clientId,
      String username,
      String password) {
    RestTemplate restTemplate = new RestTemplate();
    String tokenEndpoint = issuerUri(keycloak, realm) + "/protocol/openid-connect/token";

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "password");
    form.add("client_id", clientId);
    form.add("username", username);
    form.add("password", password);

    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

    String body =
        restTemplate.postForObject(tokenEndpoint, new HttpEntity<>(form, headers), String.class);
    try {
      JsonNode node = OBJECT_MAPPER.readTree(body);
      return node.get("access_token").asText();
    } catch (Exception e) {
      throw new IllegalStateException("Could not parse Keycloak token response: " + body, e);
    }
  }
}
