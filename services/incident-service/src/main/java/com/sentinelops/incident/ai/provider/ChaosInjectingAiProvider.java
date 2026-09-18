package com.sentinelops.incident.ai.provider;

import com.sentinelops.incident.ai.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decorates the real {@link AiProvider} with an opt-in fault-injection hook (section: "Slow,
 * unavailable, or malformed LLM responses"). The wrapped provider's own code is never modified —
 * this class only ever intercepts calls when {@code sentinelops.ai.chaos.enabled=true}, which is
 * false by default even under the dev/app profile (see {@code chaos-experiment.sh}'s llm-fault
 * experiment). Every mode still returns a {@link ProviderResult} rather than throwing, exactly like
 * every other provider — this is what proves AI unavailability can never surface as an unhandled
 * error to the incident-handling pipeline (see {@code AiTriageService}'s fallback path).
 */
public class ChaosInjectingAiProvider implements AiProvider {

  private static final Logger log = LoggerFactory.getLogger(ChaosInjectingAiProvider.class);

  private final AiProvider delegate;
  private final AiProperties properties;

  public ChaosInjectingAiProvider(AiProvider delegate, AiProperties properties) {
    this.delegate = delegate;
    this.properties = properties;
  }

  @Override
  public ProviderResult generate(TriageContext context) {
    AiProperties.Chaos chaos = properties.chaos();
    if (!chaos.enabled()) {
      return delegate.generate(context);
    }

    return switch (chaos.mode()) {
      case "slow" -> {
        log.warn(
            "AI chaos mode 'slow' active — injecting {} delay before delegating",
            chaos.injectedDelay());
        sleepUninterruptibly(chaos.injectedDelay().toMillis());
        yield delegate.generate(context);
      }
      case "unavailable" -> {
        log.warn("AI chaos mode 'unavailable' active — refusing to call the real provider");
        yield new ProviderResult.Unavailable("chaos-injected: provider unavailable");
      }
      case "malformed" -> {
        log.warn("AI chaos mode 'malformed' active — returning a deliberately invalid response");
        yield new ProviderResult.Success("{ this is not valid structured triage output %%% ]");
      }
      case "off" -> delegate.generate(context);
      default -> {
        log.warn(
            "Unknown sentinelops.ai.chaos.mode '{}' — falling back to the real provider",
            chaos.mode());
        yield delegate.generate(context);
      }
    };
  }

  @Override
  public String name() {
    return delegate.name();
  }

  private void sleepUninterruptibly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
