package com.sentinelops.incident.alerts;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.alerts.routing.RoutingConfigValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Loads {@code src/main/resources/application.yml} (the real, shipped configuration — not a
 * hand-written test fixture) via a real {@link SpringApplicationBuilder} bootstrap and proves
 * {@link AlertsProperties} binds successfully and its routing configuration passes {@link
 * RoutingConfigValidator}. This is the only way to catch a YAML-nesting mistake (e.g. a property
 * silently landing under the wrong parent key — see the bug this test would have caught in the
 * Phase 11 {@code ai.prompt} block) without a full {@code @SpringBootTest}, which in this
 * environment requires Testcontainers/Docker.
 */
class AlertsPropertiesBindingTest {

  @EnableConfigurationProperties(AlertsProperties.class)
  static class TestConfig {}

  private ConfigurableApplicationContext context;

  @AfterEach
  void tearDown() {
    if (context != null) {
      context.close();
    }
  }

  @Test
  void bindsSuccessfullyFromTheShippedApplicationYml() {
    context = new SpringApplicationBuilder(TestConfig.class).web(WebApplicationType.NONE).run();

    AlertsProperties properties = context.getBean(AlertsProperties.class);

    assertThat(properties.routing().rules()).isNotEmpty();
    assertThat(properties.routing().defaultRoute().team()).isEqualTo("unassigned");
    assertThat(properties.ingestion().supportedSchemaVersions()).contains(1);
    assertThat(properties.hmac().currentSecretId()).isNotBlank();
    assertThat(properties.correlation().window()).isPositive();

    RoutingConfigValidator validator = new RoutingConfigValidator(properties);
    assertThat(validator.validate(properties.routing())).isEmpty();
  }
}
