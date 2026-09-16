package com.sentinelops.incident.ai.provider;

/**
 * An AI-triage generation backend. See {@code DeterministicAiProvider}, {@code OllamaAiProvider},
 * {@code DisabledAiProvider}.
 */
public interface AiProvider {

  ProviderResult generate(TriageContext context);

  /** Recorded on every persisted suggestion for traceability (see {@code AiSuggestion}). */
  String name();
}
