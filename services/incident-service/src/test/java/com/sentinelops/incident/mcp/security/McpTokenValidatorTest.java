package com.sentinelops.incident.mcp.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class McpTokenValidatorTest {

  private final JwtDecoder jwtDecoder = mock(JwtDecoder.class);
  private final McpTokenValidator validator = new McpTokenValidator(jwtDecoder);

  private Jwt jwtWithRoles(List<String> roles) {
    return Jwt.withTokenValue("token")
        .header("alg", "RS256")
        .subject("responder-1")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("realm_access", Map.of("roles", roles))
        .build();
  }

  @Test
  void aMissingTokenIsRejected() {
    assertThatThrownBy(() -> validator.validate(null))
        .isInstanceOf(McpAuthenticationException.class);
    assertThatThrownBy(() -> validator.validate("")).isInstanceOf(McpAuthenticationException.class);
  }

  @Test
  void anInvalidTokenIsRejected() {
    when(jwtDecoder.decode("bad-token")).thenThrow(new JwtException("invalid signature"));
    assertThatThrownBy(() -> validator.validate("bad-token"))
        .isInstanceOf(McpAuthenticationException.class);
  }

  @Test
  void aValidViewerTokenMapsToReadOnlyScopes() {
    when(jwtDecoder.decode("good-token")).thenReturn(jwtWithRoles(List.of("VIEWER")));
    McpActor actor = validator.validate("good-token");
    assertThat(actor.subject()).isEqualTo("responder-1");
    assertThat(actor.roles()).containsExactly("VIEWER");
    assertThat(actor.hasScope(McpScope.INCIDENTS_READ)).isTrue();
    assertThat(actor.hasScope(McpScope.INCIDENTS_PROPOSE)).isFalse();
  }

  @Test
  void anExplicitScopeClaimCanOnlyNarrowNeverWidenAccess() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "RS256")
            .subject("admin-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("realm_access", Map.of("roles", List.of("ADMIN")))
            .claim("scope", "incidents:read")
            .build();
    when(jwtDecoder.decode("scoped-token")).thenReturn(jwt);

    McpActor actor = validator.validate("scoped-token");

    assertThat(actor.hasScope(McpScope.INCIDENTS_READ)).isTrue();
    // ADMIN role would normally imply admin:read, but the narrower scope claim excludes it.
    assertThat(actor.hasScope(McpScope.ADMIN_READ)).isFalse();
  }
}
