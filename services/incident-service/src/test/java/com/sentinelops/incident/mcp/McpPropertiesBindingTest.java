package com.sentinelops.incident.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.proposal.ProposalProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Loads the real shipped {@code application.yml} to catch a YAML-nesting mistake under {@code
 * sentinelops.mcp}/{@code sentinelops.proposals} before it reaches production — see {@code
 * AlertsPropertiesBindingTest} for the same pattern and the bug it would have caught in Phase 11.
 */
class McpPropertiesBindingTest {

  @EnableConfigurationProperties({McpProperties.class, ProposalProperties.class})
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

    McpProperties mcp = context.getBean(McpProperties.class);
    ProposalProperties proposals = context.getBean(ProposalProperties.class);

    assertThat(mcp.networkTransportEnabled()).isFalse();
    assertThat(mcp.endpointPath()).isEqualTo("/mcp");
    assertThat(mcp.maxPageSize()).isPositive();
    assertThat(mcp.rateLimit().maxRequestsPerWindow()).isPositive();
    assertThat(proposals.maxPerActorPerWindow()).isPositive();
    assertThat(proposals.window()).isPositive();
  }
}
