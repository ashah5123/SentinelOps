package com.sentinelops.incident.alerts.correlation;

import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.util.List;

/**
 * One deterministic, explainable correlation rule (section 8). Rules are evaluated in a fixed order
 * by {@link CorrelationEngine}; the first rule to return a non-empty decision wins — there is no
 * scoring/ranking across rules, only "does this specific rule's strict criteria match."
 */
public interface CorrelationRule {

  String id();

  int version();

  /**
   * Returns a {@link CorrelationDecision} only when this rule's criteria are strictly satisfied by
   * exactly one candidate. Must return {@link CorrelationDecision#noMatch()} rather than guessing
   * when evidence is insufficient, and {@link CorrelationDecision#ambiguous} rather than picking
   * arbitrarily when more than one candidate matches equally well — see section 8's "when evidence
   * is insufficient, create separate incidents instead of over-merging."
   */
  CorrelationDecision evaluate(CanonicalAlert alert, List<CorrelationCandidate> candidates);
}
