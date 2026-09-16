package com.sentinelops.incident.ai.embedding;

/**
 * Turns text into a fixed-dimension embedding vector for similarity search. Every implementation
 * must return vectors of the same dimension ({@link #dimensions()}) — the schema's {@code
 * runbooks.runbook_chunks.embedding} column is a fixed-width {@code vector(384)}.
 */
public interface EmbeddingProvider {

  float[] embed(String text);

  int dimensions();

  /** A short, stable name recorded alongside every suggestion/index entry for traceability. */
  String name();
}
