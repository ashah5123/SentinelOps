package com.sentinelops.incident.ai.provider;

/**
 * Used when {@code sentinelops.ai.enabled=false} or {@code sentinelops.ai.provider=disabled}.
 * Always reports unavailable, immediately, with no network call — the safe, explicit "AI assistance
 * is turned off" state (see docs/development/ai-triage.md's "how to disable" section).
 */
public class DisabledAiProvider implements AiProvider {

  @Override
  public ProviderResult generate(TriageContext context) {
    return new ProviderResult.Unavailable("AI assistance is disabled in this deployment.");
  }

  @Override
  public String name() {
    return "disabled";
  }
}
