package com.sentinelops.incident.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SentinelOps authentication/authorization configuration (Phase 7). Every field is required and
 * validated at startup — there is no implicit "open" fallback.
 */
@ConfigurationProperties(prefix = "sentinelops.security")
@Validated
public class SecurityProperties {

  /**
   * Expected {@code iss} claim value on every incoming access token — the issuer string Keycloak
   * itself embeds in tokens (its fixed {@code KC_HOSTNAME}), which is deliberately kept separate
   * from the network address used to fetch its signing keys (see {@code jwkSetUri} handling in
   * {@code SecurityConfig} and docs/development/security.md's "issuer vs. JWKS address" note — this
   * split exists because a container-internal client reaches Keycloak at a different address than
   * the one Keycloak reports as its own issuer).
   */
  @NotBlank private final String issuer;

  /** Expected {@code aud} claim value on every incoming access token. */
  @NotBlank private final String audience;

  /** Origins allowed to make cross-origin requests to the API. Empty means none are allowed. */
  @NotNull private final List<String> corsAllowedOrigins;

  private final Metrics metrics;

  public SecurityProperties(
      String issuer, String audience, List<String> corsAllowedOrigins, Metrics metrics) {
    this.issuer = issuer;
    this.audience = audience;
    this.corsAllowedOrigins = corsAllowedOrigins;
    this.metrics = metrics;
  }

  public String issuer() {
    return issuer;
  }

  public String audience() {
    return audience;
  }

  public List<String> corsAllowedOrigins() {
    return corsAllowedOrigins;
  }

  public Metrics metrics() {
    return metrics;
  }

  /**
   * Credentials for the separate, non-JWT Basic-auth chain guarding {@code /actuator/prometheus}.
   */
  public static class Metrics {
    @NotBlank private final String username;
    @NotBlank private final String password;

    public Metrics(String username, String password) {
      this.username = username;
      this.password = password;
    }

    public String username() {
      return username;
    }

    public String password() {
      return password;
    }
  }
}
