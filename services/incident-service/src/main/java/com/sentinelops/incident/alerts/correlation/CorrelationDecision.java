package com.sentinelops.incident.alerts.correlation;

import java.util.Map;
import java.util.UUID;

/**
 * The outcome of running the correlation engine against one alert (section 8). Every field required
 * by the spec is present so the decision can be persisted verbatim to {@code
 * alerts.alert_correlations} when {@link #matched()} is true.
 */
public record CorrelationDecision(
    boolean matched,
    UUID incidentId,
    String ruleId,
    int ruleVersion,
    Map<String, String> matchedFields,
    String explanation) {

  public static CorrelationDecision noMatch() {
    return new CorrelationDecision(
        false, null, null, 0, Map.of(), "No correlation rule matched an open incident.");
  }

  public static CorrelationDecision ambiguous(String explanation) {
    return new CorrelationDecision(false, null, "ambiguous", 0, Map.of(), explanation);
  }

  public static CorrelationDecision match(
      UUID incidentId,
      String ruleId,
      int ruleVersion,
      Map<String, String> matchedFields,
      String explanation) {
    return new CorrelationDecision(
        true, incidentId, ruleId, ruleVersion, matchedFields, explanation);
  }
}
