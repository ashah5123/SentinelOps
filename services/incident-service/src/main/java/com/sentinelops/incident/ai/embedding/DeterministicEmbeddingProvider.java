package com.sentinelops.incident.ai.embedding;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * A deterministic, dependency-free, network-free embedding: the classic "hashing trick" (feature
 * hashing) over lowercased word tokens — each token hashes to one of {@link #DIMENSIONS} buckets
 * with a deterministic +1/-1 sign (derived from a second hash bit, to reduce collision bias), and
 * the resulting vector is L2-normalized. This is a real, well-known, if crude, bag-of-words
 * embedding technique (used e.g. by Vowpal Wabbit) — not a placeholder that merely returns zeros —
 * and it is what makes retrieval and evaluation runnable in tests and CI without Ollama or any
 * downloaded model (see docs/development/ai-triage.md's "embedding tradeoff" section). It shares
 * the same {@value #DIMENSIONS}-dimension space as {@link OllamaEmbeddingProvider} so both can
 * populate the same {@code vector(384)} column without a schema change, but the two are not
 * numerically compatible with each other — an index built with one must be queried with the same
 * provider it was built with (see {@code sentinelops.ai.embedding.provider}).
 */
@Component
public class DeterministicEmbeddingProvider implements EmbeddingProvider {

  public static final int DIMENSIONS = 384;
  private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+");

  /**
   * A small, fixed stopword list. Without this, extremely common words (the, a, is, not, ...)
   * dominate every document's hashed vector roughly equally and inflate the apparent similarity
   * between otherwise-unrelated text — filtering them out substantially improves this simple
   * bag-of-words scheme's signal-to-noise ratio at essentially no cost.
   */
  private static final Set<String> STOPWORDS =
      Set.of(
          "a", "an", "and", "are", "as", "at", "be", "been", "but", "by", "for", "from", "had",
          "has", "have", "if", "in", "into", "is", "it", "its", "no", "not", "of", "on", "or",
          "should", "so", "than", "that", "the", "their", "then", "there", "these", "this", "to",
          "was", "were", "will", "with", "would", "you", "your");

  @Override
  public float[] embed(String text) {
    float[] vector = new float[DIMENSIONS];
    if (text == null || text.isBlank()) {
      return vector;
    }
    var matcher = TOKEN_PATTERN.matcher(text.toLowerCase(Locale.ROOT));
    while (matcher.find()) {
      String token = matcher.group();
      if (STOPWORDS.contains(token)) {
        continue;
      }
      byte[] digest = sha256(token);
      int bucket = Math.floorMod(bytesToInt(digest, 0), DIMENSIONS);
      int sign = (digest[4] & 1) == 0 ? 1 : -1;
      vector[bucket] += sign;
    }
    normalize(vector);
    return vector;
  }

  @Override
  public int dimensions() {
    return DIMENSIONS;
  }

  @Override
  public String name() {
    return "deterministic-hashing-v1";
  }

  private void normalize(float[] vector) {
    double sumSquares = 0;
    for (float v : vector) {
      sumSquares += (double) v * v;
    }
    if (sumSquares == 0) {
      return;
    }
    float norm = (float) Math.sqrt(sumSquares);
    for (int i = 0; i < vector.length; i++) {
      vector[i] /= norm;
    }
  }

  private byte[] sha256(String token) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must always be available", e);
    }
  }

  private int bytesToInt(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xFF) << 24)
        | ((bytes[offset + 1] & 0xFF) << 16)
        | ((bytes[offset + 2] & 0xFF) << 8)
        | (bytes[offset + 3] & 0xFF);
  }
}
