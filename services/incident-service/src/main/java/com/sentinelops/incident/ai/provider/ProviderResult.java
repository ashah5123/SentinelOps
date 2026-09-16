package com.sentinelops.incident.ai.provider;

/**
 * The outcome of one {@link AiProvider#generate} call. A provider never throws for an ordinary
 * "model unavailable" condition — it returns {@link Unavailable} so the pipeline can respond with a
 * clear, non-broken "AI assistance temporarily unavailable" outcome rather than a 500.
 */
public sealed interface ProviderResult {

  /**
   * Raw text the provider produced — not yet parsed/validated as {@link
   * com.sentinelops.incident.ai.TriageSuggestion}.
   */
  record Success(String rawOutput) implements ProviderResult {}

  /** The provider (or its circuit breaker) is not currently reachable/usable. */
  record Unavailable(String reason) implements ProviderResult {}

  /** The provider was reached but could not produce a result (e.g. exhausted retries). */
  record Failure(String reason) implements ProviderResult {}
}
