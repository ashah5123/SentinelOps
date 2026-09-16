package com.sentinelops.incident.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.ai.TriageSuggestion;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeterministicAiProviderTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final DeterministicAiProvider provider = new DeterministicAiProvider(objectMapper);

  @Test
  void suggestedSeverityAlwaysEchoesTheIncidentsActualSeverity() throws Exception {
    TriageContext context =
        new TriageContext(
            "Elevated latency",
            "p99 doubled",
            "SEV2",
            "DETECTED",
            "checkout-api",
            "prometheus",
            List.of(),
            List.of());

    ProviderResult result = provider.generate(context);

    assertThat(result).isInstanceOf(ProviderResult.Success.class);
    TriageSuggestion suggestion =
        objectMapper.readValue(
            ((ProviderResult.Success) result).rawOutput(), TriageSuggestion.class);
    assertThat(suggestion.suggestedSeverity()).isEqualTo("SEV2");
  }

  @Test
  void neverFabricatesACitationBeyondWhatItWasGiven() throws Exception {
    var passage =
        new TriageContext.RetrievedPassage("chunk-9", "Some Runbook", "Symptoms", "content", 0.5);
    TriageContext context =
        new TriageContext(
            "title", "desc", "SEV3", "DETECTED", "svc", "source", List.of(), List.of(passage));

    ProviderResult result = provider.generate(context);
    TriageSuggestion suggestion =
        objectMapper.readValue(
            ((ProviderResult.Success) result).rawOutput(), TriageSuggestion.class);

    assertThat(suggestion.citations()).hasSize(1);
    assertThat(suggestion.citations().get(0).chunkId()).isEqualTo("chunk-9");
  }

  @Test
  void producesNoCitationsWhenNoPassagesWereRetrieved() throws Exception {
    TriageContext context =
        new TriageContext(
            "title", "desc", "SEV4", "DETECTED", "svc", "source", List.of(), List.of());

    ProviderResult result = provider.generate(context);
    TriageSuggestion suggestion =
        objectMapper.readValue(
            ((ProviderResult.Success) result).rawOutput(), TriageSuggestion.class);

    assertThat(suggestion.citations()).isEmpty();
  }

  @Test
  void categorizesADatabaseIncidentByKeyword() throws Exception {
    TriageContext context =
        new TriageContext(
            "Connection pool exhausted",
            "database connections pending",
            "SEV2",
            "DETECTED",
            "orders-api",
            "prometheus",
            List.of(),
            List.of());

    ProviderResult result = provider.generate(context);
    TriageSuggestion suggestion =
        objectMapper.readValue(
            ((ProviderResult.Success) result).rawOutput(), TriageSuggestion.class);

    assertThat(suggestion.suggestedCategory()).isEqualTo("Database");
  }

  @Test
  void confidenceStatementDisclosesItIsARuleBasedFallback() throws Exception {
    TriageContext context =
        new TriageContext(
            "title", "desc", "SEV1", "DETECTED", "svc", "source", List.of(), List.of());

    ProviderResult result = provider.generate(context);
    TriageSuggestion suggestion =
        objectMapper.readValue(
            ((ProviderResult.Success) result).rawOutput(), TriageSuggestion.class);

    assertThat(suggestion.confidenceStatement()).containsIgnoringCase("deterministic");
  }
}
