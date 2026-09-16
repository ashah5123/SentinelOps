package com.sentinelops.incident.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects any token whose {@code aud} claim does not include the audience this service expects.
 * Without this check, a token minted for a completely different client/application but signed by
 * the same trusted issuer would otherwise be accepted.
 */
public class JwtAudienceValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error INVALID_AUDIENCE =
      new OAuth2Error("invalid_token", "The required audience is missing", null);

  private final String requiredAudience;

  public JwtAudienceValidator(String requiredAudience) {
    this.requiredAudience = requiredAudience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    if (token.getAudience() != null && token.getAudience().contains(requiredAudience)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(INVALID_AUDIENCE);
  }
}
