package com.sentinelops.incident.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class ProductionSafetyCheckTest {

  @Test
  void doesNothingOutsideProduction() {
    MockEnvironment env = new MockEnvironment().withProperty("ENVIRONMENT", "local");
    assertThatNoException().isThrownBy(() -> new ProductionSafetyCheck(env).verify());
  }

  @Test
  void rejectsTheDefaultDatabasePasswordInProduction() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("ENVIRONMENT", "production")
            .withProperty("POSTGRES_APP_PASSWORD", "change-me-local-dev-only")
            .withProperty("ACTUATOR_METRICS_PASSWORD", "a-real-secret")
            .withProperty("OAUTH2_ISSUER_URI", "https://idp.example.com/realms/sentinelops")
            .withProperty("OAUTH2_JWK_SET_URI", "https://idp.example.com/realms/sentinelops/certs");

    assertThatThrownBy(() -> new ProductionSafetyCheck(env).verify())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("POSTGRES_APP_PASSWORD");
  }

  @Test
  void rejectsAWildcardCorsAllowlistInProduction() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("ENVIRONMENT", "prod")
            .withProperty("POSTGRES_APP_PASSWORD", "a-real-secret")
            .withProperty("ACTUATOR_METRICS_PASSWORD", "a-real-secret")
            .withProperty("OAUTH2_ISSUER_URI", "https://idp.example.com/realms/sentinelops")
            .withProperty("OAUTH2_JWK_SET_URI", "https://idp.example.com/realms/sentinelops/certs")
            .withProperty("CORS_ALLOWED_ORIGINS", "https://app.example.com,*");

    assertThatThrownBy(() -> new ProductionSafetyCheck(env).verify())
        .hasMessageContaining("CORS_ALLOWED_ORIGINS");
  }

  @Test
  void rejectsAPlaintextIssuerUriInProduction() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("ENVIRONMENT", "production")
            .withProperty("POSTGRES_APP_PASSWORD", "a-real-secret")
            .withProperty("ACTUATOR_METRICS_PASSWORD", "a-real-secret")
            .withProperty("OAUTH2_ISSUER_URI", "http://idp.example.com/realms/sentinelops")
            .withProperty("OAUTH2_JWK_SET_URI", "https://idp.example.com/realms/sentinelops/certs");

    assertThatThrownBy(() -> new ProductionSafetyCheck(env).verify())
        .hasMessageContaining("OAUTH2_ISSUER_URI");
  }

  @Test
  void rejectsDebugLoggingInProduction() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("ENVIRONMENT", "production")
            .withProperty("POSTGRES_APP_PASSWORD", "a-real-secret")
            .withProperty("ACTUATOR_METRICS_PASSWORD", "a-real-secret")
            .withProperty("OAUTH2_ISSUER_URI", "https://idp.example.com/realms/sentinelops")
            .withProperty("OAUTH2_JWK_SET_URI", "https://idp.example.com/realms/sentinelops/certs")
            .withProperty("LOG_LEVEL", "DEBUG");

    assertThatThrownBy(() -> new ProductionSafetyCheck(env).verify())
        .hasMessageContaining("LOG_LEVEL");
  }

  @Test
  void acceptsFullyConfiguredProductionSettings() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("ENVIRONMENT", "production")
            .withProperty("POSTGRES_APP_PASSWORD", "a-real-secret")
            .withProperty("ACTUATOR_METRICS_PASSWORD", "another-real-secret")
            .withProperty("OAUTH2_ISSUER_URI", "https://idp.example.com/realms/sentinelops")
            .withProperty("OAUTH2_JWK_SET_URI", "https://idp.example.com/realms/sentinelops/certs")
            .withProperty("CORS_ALLOWED_ORIGINS", "https://app.example.com")
            .withProperty("LOG_LEVEL", "INFO");

    assertThatNoException().isThrownBy(() -> new ProductionSafetyCheck(env).verify());
    assertThat(env.getProperty("ENVIRONMENT")).isEqualTo("production");
  }
}
