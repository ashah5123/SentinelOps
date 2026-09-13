package com.sentinelops.incident.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CorrelationIdsTest {

  @Test
  void generateProducesAValidUuidString() {
    String id = CorrelationIds.generate();
    assertThat(UUID.fromString(id)).isNotNull();
  }

  @Test
  void orGenerateReturnsCandidateWhenPresent() {
    assertThat(CorrelationIds.orGenerate("client-supplied-id")).isEqualTo("client-supplied-id");
  }

  @Test
  void orGenerateTrimsWhitespace() {
    assertThat(CorrelationIds.orGenerate("  padded-id  ")).isEqualTo("padded-id");
  }

  @Test
  void orGenerateFallsBackWhenNull() {
    assertThat(CorrelationIds.orGenerate(null)).isNotBlank();
  }

  @Test
  void orGenerateFallsBackWhenBlank() {
    assertThat(CorrelationIds.orGenerate("   ")).isNotBlank();
  }
}
