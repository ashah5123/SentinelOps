package com.sentinelops.incident.ai.embedding;

/**
 * Thrown when an embedding cannot be produced (provider unreachable, timed out, or misconfigured).
 */
public class EmbeddingUnavailableException extends RuntimeException {

  public EmbeddingUnavailableException(String message) {
    super(message);
  }

  public EmbeddingUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
