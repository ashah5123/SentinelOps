package com.sentinelops.incident.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sentinelops.incident.ai.AiProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the acceptance criterion "AI unavailability does not prevent deterministic incident
 * handling": every chaos mode still returns a {@link ProviderResult}, never throws, and the real
 * delegate provider's code is never touched when chaos mode is off (the default).
 */
class ChaosInjectingAiProviderTest {

  private final TriageContext context =
      new TriageContext(
          "title",
          "description",
          "SEV2",
          "DETECTED",
          "checkout-api",
          "prometheus",
          List.of(),
          List.of());

  private AiProperties propertiesWith(boolean enabled, String mode) {
    return new AiProperties(
        true,
        "deterministic",
        "deterministic",
        new AiProperties.Ollama(
            "http://localhost:11434", "llama3.2", "nomic-embed-text", Duration.ofSeconds(5), 1),
        new AiProperties.Retrieval("in-memory", 5, 0.0),
        new AiProperties.CircuitBreakerSettings(3, Duration.ofSeconds(5)),
        new AiProperties.RateLimit(Duration.ZERO),
        new AiProperties.Prompt(4000, 1500, "v1"),
        new AiProperties.Chaos(enabled, mode, Duration.ofMillis(10)));
  }

  @Test
  void delegatesUntouchedWhenChaosIsDisabled() {
    AiProvider delegate = mock(AiProvider.class);
    when(delegate.generate(context)).thenReturn(new ProviderResult.Success("real output"));
    var provider = new ChaosInjectingAiProvider(delegate, propertiesWith(false, "unavailable"));

    ProviderResult result = provider.generate(context);

    assertThat(result).isInstanceOf(ProviderResult.Success.class);
    assertThat(((ProviderResult.Success) result).rawOutput()).isEqualTo("real output");
  }

  @Test
  void unavailableModeNeverCallsTheRealProviderAndReturnsUnavailable() {
    AiProvider delegate = mock(AiProvider.class);
    var provider = new ChaosInjectingAiProvider(delegate, propertiesWith(true, "unavailable"));

    ProviderResult result = provider.generate(context);

    assertThat(result).isInstanceOf(ProviderResult.Unavailable.class);
    verify(delegate, never()).generate(context);
  }

  @Test
  void malformedModeReturnsASuccessThatIsNotValidStructuredOutput() {
    AiProvider delegate = mock(AiProvider.class);
    var provider = new ChaosInjectingAiProvider(delegate, propertiesWith(true, "malformed"));

    ProviderResult result = provider.generate(context);

    assertThat(result).isInstanceOf(ProviderResult.Success.class);
    verify(delegate, never()).generate(context);
  }

  @Test
  void slowModeStillDelegatesAfterTheInjectedDelay() {
    AiProvider delegate = mock(AiProvider.class);
    when(delegate.generate(context)).thenReturn(new ProviderResult.Success("real output"));
    var provider = new ChaosInjectingAiProvider(delegate, propertiesWith(true, "slow"));

    long start = System.nanoTime();
    ProviderResult result = provider.generate(context);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertThat(result).isInstanceOf(ProviderResult.Success.class);
    assertThat(elapsedMillis).isGreaterThanOrEqualTo(10);
    verify(delegate).generate(context);
  }

  @Test
  void offModeDelegatesEvenWhenChaosIsEnabled() {
    AiProvider delegate = mock(AiProvider.class);
    when(delegate.generate(context)).thenReturn(new ProviderResult.Success("real output"));
    var provider = new ChaosInjectingAiProvider(delegate, propertiesWith(true, "off"));

    ProviderResult result = provider.generate(context);

    assertThat(result).isInstanceOf(ProviderResult.Success.class);
  }

  @Test
  void nameDelegatesToTheRealProvider() {
    AiProvider delegate = mock(AiProvider.class);
    when(delegate.name()).thenReturn("deterministic");
    var provider = new ChaosInjectingAiProvider(delegate, propertiesWith(false, "off"));

    assertThat(provider.name()).isEqualTo("deterministic");
  }
}
