package com.sentinelops.incident.ai.embedding;

/**
 * Pure cosine-similarity math, shared by the in-memory retrieval path (tests/evaluation) and used
 * as the reference implementation the pgvector {@code <=>} cosine-distance operator computes
 * natively in the database (similarity = 1 - distance) — see {@code
 * retrieval/PgVectorRunbookRetriever.java}.
 */
public final class CosineSimilarity {

  private CosineSimilarity() {}

  public static double of(float[] a, float[] b) {
    if (a.length != b.length) {
      throw new IllegalArgumentException(
          "Vector dimension mismatch: " + a.length + " vs " + b.length);
    }
    double dot = 0;
    double normA = 0;
    double normB = 0;
    for (int i = 0; i < a.length; i++) {
      dot += (double) a[i] * b[i];
      normA += (double) a[i] * a[i];
      normB += (double) b[i] * b[i];
    }
    if (normA == 0 || normB == 0) {
      return 0.0;
    }
    return dot / (Math.sqrt(normA) * Math.sqrt(normB));
  }
}
