package com.sentinelops.incident.alerts.correlation;

import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link CorrelationRule} in order (Spring injects the list ordered by {@code @Order})
 * and returns the first decisive match. A rule that finds its criteria ambiguous does not stop
 * evaluation of later rules, but if nothing ultimately matches, the first ambiguous decision (if
 * any) is returned instead of a plain no-match, purely so metrics/logging can distinguish "no
 * rule's criteria applied" from "a rule's criteria applied to more than one candidate" — both
 * outcomes result in the same action (create a separate incident), per section 8.
 */
@Component
public class CorrelationEngine {

  private final List<CorrelationRule> rules;

  public CorrelationEngine(List<CorrelationRule> rules) {
    this.rules = rules;
  }

  public CorrelationDecision correlate(
      CanonicalAlert alert, List<CorrelationCandidate> candidates) {
    CorrelationDecision firstAmbiguous = null;
    for (CorrelationRule rule : rules) {
      CorrelationDecision decision = rule.evaluate(alert, candidates);
      if (decision.matched()) {
        return decision;
      }
      if ("ambiguous".equals(decision.ruleId()) && firstAmbiguous == null) {
        firstAmbiguous = decision;
      }
    }
    return firstAmbiguous != null ? firstAmbiguous : CorrelationDecision.noMatch();
  }
}
