package com.sentinelops.incident.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Loads the real shipped {@code application.yml} to catch a YAML-nesting mistake under {@code
 * sentinelops.remediation} before it reaches production — see {@code McpPropertiesBindingTest} for
 * the same pattern.
 */
class RemediationPropertiesBindingTest {

  @EnableConfigurationProperties(RemediationProperties.class)
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

    RemediationProperties properties = context.getBean(RemediationProperties.class);

    assertThat(properties.allowedCacheNamespaces()).isNotEmpty();
    assertThat(properties.allowedQueueNames()).isNotEmpty();
    assertThat(properties.allowedDiagnosticCommands()).isNotEmpty();
    assertThat(properties.maxConcurrentExecutions()).isPositive();
    assertThat(properties.lockLeaseDuration().isPositive()).isTrue();
    assertThat(properties.schedulerPollingInterval().isPositive()).isTrue();
    assertThat(properties.schedulerBatchSize()).isPositive();
    assertThat(properties.diagnosticCommandTimeout().isPositive()).isTrue();
    assertThat(properties.blastRadius().maxResourceCount()).isPositive();
    assertThat(properties.blastRadius().restrictedEnvironments()).contains("production");
    assertThat(properties.circuitBreaker().failureThreshold()).isPositive();
    assertThat(properties.circuitBreaker().openDuration().isPositive()).isTrue();
    assertThat(properties.maintenanceWindow().daysOfWeek()).isNotEmpty();
    assertThat(properties.maintenanceWindow().zoneId()).isNotBlank();
  }
}
