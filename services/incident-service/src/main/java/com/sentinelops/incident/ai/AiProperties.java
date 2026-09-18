package com.sentinelops.incident.ai;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * AI-assisted triage configuration (Phase 11). Disabled by default ({@code enabled: false}) — this
 * is an optional, human-reviewed assistance feature, never required for the core
 * incident-management application to function. See docs/development/ai-triage.md.
 */
@ConfigurationProperties(prefix = "sentinelops.ai")
@Validated
public class AiProperties {

  private final boolean enabled;

  /** "ollama" | "deterministic" | "disabled" — see provider/AiProviderFactory. */
  @NotBlank private final String provider;

  /** "ollama" | "deterministic" — see embedding/EmbeddingProviderFactory. */
  @NotBlank private final String embeddingProvider;

  private final Ollama ollama;
  private final Retrieval retrieval;
  private final CircuitBreakerSettings circuitBreaker;
  private final RateLimit rateLimit;
  private final Prompt prompt;
  private final Chaos chaos;

  public AiProperties(
      boolean enabled,
      String provider,
      String embeddingProvider,
      Ollama ollama,
      Retrieval retrieval,
      CircuitBreakerSettings circuitBreaker,
      RateLimit rateLimit,
      Prompt prompt,
      Chaos chaos) {
    this.enabled = enabled;
    this.provider = provider;
    this.embeddingProvider = embeddingProvider;
    this.ollama = ollama;
    this.retrieval = retrieval;
    this.circuitBreaker = circuitBreaker;
    this.rateLimit = rateLimit;
    this.prompt = prompt;
    this.chaos = chaos;
  }

  public boolean enabled() {
    return enabled;
  }

  public String provider() {
    return provider;
  }

  public String embeddingProvider() {
    return embeddingProvider;
  }

  public Ollama ollama() {
    return ollama;
  }

  public Retrieval retrieval() {
    return retrieval;
  }

  public CircuitBreakerSettings circuitBreaker() {
    return circuitBreaker;
  }

  public RateLimit rateLimit() {
    return rateLimit;
  }

  public Prompt prompt() {
    return prompt;
  }

  public Chaos chaos() {
    return chaos;
  }

  /** Local Ollama endpoint/model configuration — never a paid/external API. */
  public static class Ollama {
    @NotBlank private final String baseUrl;
    @NotBlank private final String model;
    @NotBlank private final String embeddingModel;
    private final Duration timeout;

    @Min(0)
    private final int maxRetries;

    public Ollama(
        String baseUrl, String model, String embeddingModel, Duration timeout, int maxRetries) {
      this.baseUrl = baseUrl;
      this.model = model;
      this.embeddingModel = embeddingModel;
      this.timeout = timeout;
      this.maxRetries = maxRetries;
    }

    public String baseUrl() {
      return baseUrl;
    }

    public String model() {
      return model;
    }

    public String embeddingModel() {
      return embeddingModel;
    }

    public Duration timeout() {
      return timeout;
    }

    /**
     * Only retryable failures (timeouts, connection resets) are retried, and only this many times.
     */
    public int maxRetries() {
      return maxRetries;
    }
  }

  public static class Retrieval {
    /** "pgvector" (preferred, default) or "in-memory" (documented tradeoff — see the docs). */
    @NotBlank private final String backend;

    @Min(1)
    private final int topK;

    private final double minScore;

    public Retrieval(String backend, int topK, double minScore) {
      this.backend = backend;
      this.topK = topK;
      this.minScore = minScore;
    }

    public String backend() {
      return backend;
    }

    public int topK() {
      return topK;
    }

    public double minScore() {
      return minScore;
    }
  }

  public static class CircuitBreakerSettings {
    @Min(1)
    private final int failureThreshold;

    private final Duration openDuration;

    public CircuitBreakerSettings(int failureThreshold, Duration openDuration) {
      this.failureThreshold = failureThreshold;
      this.openDuration = openDuration;
    }

    public int failureThreshold() {
      return failureThreshold;
    }

    public Duration openDuration() {
      return openDuration;
    }
  }

  public static class RateLimit {
    private final Duration cooldown;

    public RateLimit(Duration cooldown) {
      this.cooldown = cooldown;
    }

    /** Minimum time between two generation requests for the same incident. */
    public Duration cooldown() {
      return cooldown;
    }
  }

  public static class Prompt {
    @Min(1)
    private final int maxDescriptionChars;

    @Min(1)
    private final int maxPassageChars;

    @NotBlank private final String templateVersion;

    public Prompt(int maxDescriptionChars, int maxPassageChars, String templateVersion) {
      this.maxDescriptionChars = maxDescriptionChars;
      this.maxPassageChars = maxPassageChars;
      this.templateVersion = templateVersion;
    }

    public int maxDescriptionChars() {
      return maxDescriptionChars;
    }

    public int maxPassageChars() {
      return maxPassageChars;
    }

    public String templateVersion() {
      return templateVersion;
    }
  }

  /**
   * Phase 15 chaos-testing hook (section: "Slow, unavailable, or malformed LLM responses").
   * Disabled by default, even under the dev/app profile — must be explicitly opted into, exactly
   * like {@code sentinelops.remediation}'s chaos-adjacent fault-injection hooks. Honored only by
   * {@code ChaosInjectingAiProvider}, which decorates the real provider bean rather than modifying
   * it — the real provider code paths are never changed by this flag.
   */
  public static class Chaos {
    private final boolean enabled;

    /** "off" | "slow" | "unavailable" | "malformed". */
    @NotBlank private final String mode;

    private final Duration injectedDelay;

    public Chaos(boolean enabled, String mode, Duration injectedDelay) {
      this.enabled = enabled;
      this.mode = mode;
      this.injectedDelay = injectedDelay;
    }

    public boolean enabled() {
      return enabled;
    }

    public String mode() {
      return mode;
    }

    public Duration injectedDelay() {
      return injectedDelay;
    }
  }
}
