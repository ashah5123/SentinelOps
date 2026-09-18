package com.sentinelops.incident.slo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class SloCatalogTest {

  private final SloCatalog catalog = new SloCatalog();

  @Test
  void loadsTheShippedCatalogWithSevenDefinitions() {
    assertThat(catalog.all()).hasSize(7);
  }

  @Test
  void everyDefinitionHasAUniqueIdAndAValidQuery() {
    Set<String> ids =
        catalog.all().stream().map(SloDefinition::id).collect(java.util.stream.Collectors.toSet());
    assertThat(ids).hasSize(catalog.all().size());
    catalog.all().forEach(d -> assertThat(d.sliQuery()).isNotBlank());
  }

  @Test
  void coversEveryRequiredCapability() {
    Set<String> ids =
        catalog.all().stream().map(SloDefinition::id).collect(java.util.stream.Collectors.toSet());
    assertThat(ids)
        .contains(
            "alert-telemetry-ingestion",
            "incident-creation-correlation",
            "operator-console-availability",
            "notification-delivery",
            "ai-triage-availability",
            "remediation-execution-rollback",
            "data-durability-recovery");
  }
}
