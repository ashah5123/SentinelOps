package com.sentinelops.incident.slo;

/**
 * The evaluated status of one SLO. {@code UNKNOWN} (not an error) is reported whenever Prometheus
 * is unreachable or the configured query returns no data — the graceful-degradation path.
 */
public record SloStatus(
    String id,
    String name,
    String description,
    double objective,
    int windowDays,
    boolean known,
    Double currentSli,
    Double errorBudgetRemaining,
    Double burnRate,
    String severity) {

  public static SloStatus unknown(SloDefinition definition) {
    return new SloStatus(
        definition.id(),
        definition.name(),
        definition.description(),
        definition.objective(),
        definition.windowDays(),
        false,
        null,
        null,
        null,
        "UNKNOWN");
  }

  public static SloStatus known(SloDefinition definition, ErrorBudgetResult result) {
    return new SloStatus(
        definition.id(),
        definition.name(),
        definition.description(),
        definition.objective(),
        definition.windowDays(),
        true,
        result.currentSli(),
        result.remainingFraction(),
        result.burnRate(),
        result.severity().name());
  }
}
