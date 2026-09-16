package com.sentinelops.incident.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtAudienceValidatorTest {

  private final JwtAudienceValidator validator =
      new JwtAudienceValidator("sentinelops-incident-api");

  private Jwt jwtWithAudience(List<String> audience) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .claim("sub", "user-1")
        .audience(audience)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .build();
  }

  @Test
  void acceptsATokenThatIncludesTheRequiredAudience() {
    OAuth2TokenValidatorResult result =
        validator.validate(jwtWithAudience(List.of("sentinelops-incident-api", "account")));

    assertThat(result.hasErrors()).isFalse();
  }

  @Test
  void rejectsATokenMissingTheRequiredAudience() {
    OAuth2TokenValidatorResult result = validator.validate(jwtWithAudience(List.of("account")));

    assertThat(result.hasErrors()).isTrue();
  }

  @Test
  void rejectsATokenWithNoAudienceClaimAtAll() {
    Jwt jwt =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("sub", "user-1")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .build();

    assertThat(validator.validate(jwt).hasErrors()).isTrue();
  }
}
