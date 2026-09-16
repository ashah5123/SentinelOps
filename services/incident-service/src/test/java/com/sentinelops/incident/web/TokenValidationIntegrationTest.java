package com.sentinelops.incident.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.support.AbstractIntegrationTest;
import com.sentinelops.incident.support.KeycloakTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

/**
 * Validates this service's OAuth2 resource-server configuration against a real, local Keycloak
 * instance rather than mocked authentication — signature, issuer, audience, and expiry are all
 * exercised with genuinely issued (or genuinely invalid) tokens.
 */
class TokenValidationIntegrationTest extends AbstractIntegrationTest {

  private ResponseEntity<ProblemDetail> callWithToken(String token) {
    HttpHeaders headers = new HttpHeaders();
    if (token != null) {
      headers.setBearerAuth(token);
    }
    return restTemplate.exchange(
        "/api/v1/incidents", HttpMethod.GET, new HttpEntity<>(headers), ProblemDetail.class);
  }

  @Test
  void requestWithNoAuthorizationHeaderIsRejected() {
    assertThat(callWithToken(null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void malformedTokenIsRejected() {
    assertThat(callWithToken("not-a-real-jwt").getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void validTokenFromTheConfiguredRealmIsAccepted() {
    ResponseEntity<ProblemDetail> response = callWithToken(tokenFor("VIEWER"));
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  void tamperedSignatureIsRejected() {
    String realToken = tokenFor("VIEWER");
    // Flip the last character of the signature segment — the payload/header decode fine, but the
    // signature no longer verifies against Keycloak's real public key.
    String tampered =
        realToken.substring(0, realToken.length() - 1) + (realToken.endsWith("A") ? "B" : "A");
    assertThat(callWithToken(tampered).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void tokenSignedByAnotherRealmIsRejectedAsWrongIssuer() {
    String otherRealmToken =
        KeycloakTestSupport.fetchAccessToken(
            KEYCLOAK,
            "sentinelops-other",
            "other-issuer-client",
            "other-realm-user",
            "other-realm-user-local-only");

    assertThat(callWithToken(otherRealmToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void tokenWithoutTheRequiredAudienceIsRejected() {
    String wrongAudienceToken =
        KeycloakTestSupport.fetchAccessToken(
            KEYCLOAK,
            "sentinelops",
            "sentinelops-wrong-audience",
            "viewer-demo",
            "viewer-demo-local-only");

    assertThat(callWithToken(wrongAudienceToken).getStatusCode())
        .isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void expiredTokenIsRejected() throws InterruptedException {
    String shortLivedToken =
        KeycloakTestSupport.fetchAccessToken(
            KEYCLOAK,
            "sentinelops",
            "sentinelops-api-short-lived",
            "viewer-demo",
            "viewer-demo-local-only");

    Thread.sleep(Duration.ofSeconds(4).toMillis());

    assertThat(callWithToken(shortLivedToken).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }
}
