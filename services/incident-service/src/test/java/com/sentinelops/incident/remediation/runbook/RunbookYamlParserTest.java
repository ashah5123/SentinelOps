package com.sentinelops.incident.remediation.runbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinelops.incident.remediation.adapters.RemediationActionType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class RunbookYamlParserTest {

  private final RunbookYamlParser parser = new RunbookYamlParser();

  @Test
  void parsesAllShippedExampleRunbooks() throws IOException {
    for (String file :
        new String[] {
          "remediation-runbooks/clear-triage-search-cache.yaml",
          "remediation-runbooks/restart-checkout-service.yaml",
          "remediation-runbooks/scale-down-payment-worker.yaml"
        }) {
      String yaml = readClasspathResource(file);
      RunbookDefinition definition = parser.parse(yaml);
      assertThat(definition.steps()).isNotEmpty();
    }
  }

  @Test
  void lowRiskRunbookParsesExpectedShape() throws IOException {
    RunbookDefinition definition =
        parser.parse(readClasspathResource("remediation-runbooks/clear-triage-search-cache.yaml"));

    assertThat(definition.slug()).isEqualTo("clear-triage-search-cache");
    assertThat(definition.riskClassification()).isEqualTo(RiskClassification.LOW);
    assertThat(definition.steps()).hasSize(2);
    assertThat(definition.steps().get(0).adapterType())
        .isEqualTo(RemediationActionType.CACHE_CLEAR);
    assertThat(definition.rollbackSteps()).isEmpty();
  }

  @Test
  void highRiskRunbookHasRollbackSteps() throws IOException {
    RunbookDefinition definition =
        parser.parse(readClasspathResource("remediation-runbooks/scale-down-payment-worker.yaml"));

    assertThat(definition.riskClassification()).isEqualTo(RiskClassification.HIGH);
    assertThat(definition.rollbackSteps()).hasSizeGreaterThanOrEqualTo(1);
  }

  @Test
  void rejectsUnknownAdapterType() {
    String yaml =
        """
        slug: bad-runbook
        version: 1
        title: Bad runbook
        riskClassification: LOW
        steps:
          - name: do-something
            adapter: DELETE_EVERYTHING
        """;
    assertThatThrownBy(() -> parser.parse(yaml)).isInstanceOf(RunbookValidationException.class);
  }

  @Test
  void rejectsMissingRequiredField() {
    String yaml =
        """
        version: 1
        title: Missing slug
        riskClassification: LOW
        steps:
          - name: step-one
            adapter: HEALTH_CHECK
        """;
    assertThatThrownBy(() -> parser.parse(yaml)).isInstanceOf(RunbookValidationException.class);
  }

  @Test
  void rejectsOutOfRangeRetries() {
    String yaml =
        """
        slug: too-many-retries
        version: 1
        title: Too many retries
        riskClassification: LOW
        steps:
          - name: step-one
            adapter: HEALTH_CHECK
            maxRetries: 99
        """;
    assertThatThrownBy(() -> parser.parse(yaml)).isInstanceOf(RunbookValidationException.class);
  }

  @Test
  void rejectsBlankDocument() {
    assertThatThrownBy(() -> parser.parse("   ")).isInstanceOf(RunbookValidationException.class);
  }

  @Test
  void rejectsNonMappingTopLevel() {
    assertThatThrownBy(() -> parser.parse("- just\n- a\n- list\n"))
        .isInstanceOf(RunbookValidationException.class);
  }

  private String readClasspathResource(String path) throws IOException {
    try (var stream = new ClassPathResource(path).getInputStream()) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
