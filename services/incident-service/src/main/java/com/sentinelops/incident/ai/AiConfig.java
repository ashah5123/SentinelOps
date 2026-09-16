package com.sentinelops.incident.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.ai.embedding.DeterministicEmbeddingProvider;
import com.sentinelops.incident.ai.embedding.EmbeddingProvider;
import com.sentinelops.incident.ai.embedding.OllamaEmbeddingProvider;
import com.sentinelops.incident.ai.provider.AiProvider;
import com.sentinelops.incident.ai.provider.DeterministicAiProvider;
import com.sentinelops.incident.ai.provider.DisabledAiProvider;
import com.sentinelops.incident.ai.provider.OllamaAiProvider;
import com.sentinelops.incident.ai.provider.SimpleCircuitBreaker;
import com.sentinelops.incident.ai.retrieval.InMemoryRunbookRetriever;
import com.sentinelops.incident.ai.retrieval.PgVectorRunbookRepository;
import com.sentinelops.incident.ai.retrieval.PgVectorRunbookRetriever;
import com.sentinelops.incident.ai.retrieval.RunbookRetriever;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the {@link AiProvider} and {@link EmbeddingProvider} implementation from configuration
 * (see {@link AiProperties}) — the rest of the AI pipeline depends only on these interfaces, never
 * on a concrete implementation, so swapping providers never requires a code change.
 */
@Configuration
public class AiConfig {

  @Bean
  public SimpleCircuitBreaker aiCircuitBreaker(AiProperties properties) {
    return new SimpleCircuitBreaker(
        properties.circuitBreaker().failureThreshold(), properties.circuitBreaker().openDuration());
  }

  @Bean
  public AiProvider aiProvider(
      AiProperties properties, ObjectMapper objectMapper, SimpleCircuitBreaker circuitBreaker) {
    if (!properties.enabled()) {
      return new DisabledAiProvider();
    }
    return switch (properties.provider()) {
      case "ollama" -> new OllamaAiProvider(properties, objectMapper, circuitBreaker);
      case "deterministic" -> new DeterministicAiProvider(objectMapper);
      case "disabled" -> new DisabledAiProvider();
      default ->
          throw new IllegalStateException(
              "Unknown sentinelops.ai.provider: "
                  + properties.provider()
                  + " (expected ollama, deterministic, or disabled)");
    };
  }

  @Bean
  public EmbeddingProvider embeddingProvider(
      AiProperties properties,
      ObjectMapper objectMapper,
      DeterministicEmbeddingProvider deterministic) {
    return switch (properties.embeddingProvider()) {
      case "ollama" -> new OllamaEmbeddingProvider(properties, objectMapper);
      case "deterministic" -> deterministic;
      default ->
          throw new IllegalStateException(
              "Unknown sentinelops.ai.embedding-provider: "
                  + properties.embeddingProvider()
                  + " (expected ollama or deterministic)");
    };
  }

  /**
   * Preferred: PostgreSQL + pgvector (see {@link PgVectorRunbookRetriever}). {@code in-memory} is
   * the documented, network-free alternative used by tests and the evaluation harness (see {@link
   * InMemoryRunbookRetriever}) and is also selectable in a real deployment that has no
   * database-level pgvector access.
   */
  @Bean
  public RunbookRetriever runbookRetriever(
      AiProperties properties,
      PgVectorRunbookRepository repository,
      EmbeddingProvider embeddingProvider) {
    return switch (properties.retrieval().backend()) {
      case "pgvector" -> new PgVectorRunbookRetriever(repository, embeddingProvider);
      case "in-memory" -> InMemoryRunbookRetriever.loadFromClasspath(embeddingProvider);
      default ->
          throw new IllegalStateException(
              "Unknown sentinelops.ai.retrieval.backend: "
                  + properties.retrieval().backend()
                  + " (expected pgvector or in-memory)");
    };
  }
}
