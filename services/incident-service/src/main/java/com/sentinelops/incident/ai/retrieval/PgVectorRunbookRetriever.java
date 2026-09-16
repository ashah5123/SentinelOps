package com.sentinelops.incident.ai.retrieval;

import com.sentinelops.incident.ai.embedding.EmbeddingProvider;
import java.util.List;

/**
 * The preferred, production retrieval path: PostgreSQL + pgvector (see {@link
 * PgVectorRunbookRepository}).
 */
public class PgVectorRunbookRetriever implements RunbookRetriever {

  private final PgVectorRunbookRepository repository;
  private final EmbeddingProvider embeddingProvider;

  public PgVectorRunbookRetriever(
      PgVectorRunbookRepository repository, EmbeddingProvider embeddingProvider) {
    this.repository = repository;
    this.embeddingProvider = embeddingProvider;
  }

  @Override
  public List<RetrievedChunk> search(String query, int topK, double minScore) {
    float[] queryEmbedding = embeddingProvider.embed(query);
    return repository.search(queryEmbedding, topK, minScore);
  }
}
