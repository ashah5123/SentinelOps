package com.sentinelops.incident.ai.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelops.incident.ai.embedding.DeterministicEmbeddingProvider;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Exercises real retrieval quality (using the deterministic, network-free embedding provider)
 * against the actual curated runbooks — the same numbers this class's assertions rely on are reused
 * by the evaluation harness (see {@code AiEvaluationTest}) for the recall/precision/MRR report.
 */
class InMemoryRunbookRetrieverTest {

  private static InMemoryRunbookRetriever retriever;

  @BeforeAll
  static void setUp() {
    retriever = InMemoryRunbookRetriever.loadFromClasspath(new DeterministicEmbeddingProvider());
  }

  @Test
  void indexesAllChunksFromAllCuratedRunbooks() {
    assertThat(retriever.size()).isGreaterThanOrEqualTo(7 * 4); // 7 runbooks x >=4 sections each
  }

  @Test
  void returnsAtMostTopKResults() {
    List<RetrievedChunk> results = retriever.search("database connection pool exhausted", 2, -1.0);
    assertThat(results).hasSizeLessThanOrEqualTo(2);
  }

  @Test
  void neverReturnsAResultBelowTheMinimumScore() {
    List<RetrievedChunk> results =
        retriever.search("database connection pool exhausted timeout", 10, 0.3);
    assertThat(results).allSatisfy(r -> assertThat(r.score()).isGreaterThanOrEqualTo(0.3));
  }

  @Test
  void aDatabaseConnectionQueryRanksTheDatabaseRunbookHighest() {
    List<RetrievedChunk> results =
        retriever.search("database connection pool exhausted timeout hikaricp", 3, -1.0);
    assertThat(results).isNotEmpty();
    assertThat(results.get(0).sourceSlug()).isEqualTo("database-connection-exhaustion");
  }

  @Test
  void anAuthenticationQueryRanksTheAuthRunbookHighest() {
    List<RetrievedChunk> results =
        retriever.search("401 unauthorized token expired authentication failure", 3, -1.0);
    assertThat(results).isNotEmpty();
    assertThat(results.get(0).sourceSlug()).isEqualTo("authentication-authorization-failures");
  }

  @Test
  void aConsumerLagQueryRanksTheMessagingRunbookHighest() {
    List<RetrievedChunk> results =
        retriever.search("kafka consumer group lag redpanda partitions falling behind", 3, -1.0);
    assertThat(results).isNotEmpty();
    assertThat(results.get(0).sourceSlug()).isEqualTo("message-consumer-lag");
  }

  @Test
  void anUnrelatedQueryReturnsNoResultsAboveAReasonableThreshold() {
    List<RetrievedChunk> results =
        retriever.search("recipe for chocolate chip cookies baking temperature", 5, 0.15);
    assertThat(results).isEmpty();
  }
}
