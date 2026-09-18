package com.sentinelops.incident.slo;

/**
 * One service-level objective (section: "SLO and resilience validation"). {@code sliQuery} is a
 * PromQL expression expected to evaluate to a single instant-vector value in the range [0, 1] (a
 * success ratio) — the query itself is configuration, not code, so an operator can tune a threshold
 * or fix a metric-name drift without a Java change (mirrors the policy-as-code and routing-rules
 * pattern already used elsewhere in this codebase).
 */
public record SloDefinition(
    String id, String name, String description, String sliQuery, double objective, int windowDays) {

  public SloDefinition {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("SLO id is required");
    }
    if (objective <= 0 || objective >= 1) {
      throw new IllegalArgumentException("SLO objective must be between 0 and 1 exclusive: " + id);
    }
    if (windowDays < 1) {
      throw new IllegalArgumentException("SLO windowDays must be positive: " + id);
    }
  }
}
