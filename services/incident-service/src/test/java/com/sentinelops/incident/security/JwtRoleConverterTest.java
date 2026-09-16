package com.sentinelops.incident.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtRoleConverterTest {

  private final JwtRoleConverter converter = new JwtRoleConverter();

  private Jwt jwtWithRealmRoles(List<String> roles) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("sub", "user-1")
        .claim("realm_access", Map.of("roles", roles))
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }

  @Test
  void mapsKnownRealmRolesToPrefixedAuthorities() {
    var authorities = converter.convert(jwtWithRealmRoles(List.of("ADMIN", "responder")));

    assertThat(authorities.stream().map(GrantedAuthority::getAuthority))
        .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_RESPONDER");
  }

  @Test
  void ignoresUnknownRoles() {
    var authorities =
        converter.convert(
            jwtWithRealmRoles(List.of("VIEWER", "offline_access", "uma_authorization")));

    assertThat(authorities.stream().map(GrantedAuthority::getAuthority))
        .containsExactly("ROLE_VIEWER");
  }

  @Test
  void returnsNoAuthoritiesWhenRealmAccessClaimIsAbsent() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

    assertThat(converter.convert(jwt)).isEmpty();
  }
}
