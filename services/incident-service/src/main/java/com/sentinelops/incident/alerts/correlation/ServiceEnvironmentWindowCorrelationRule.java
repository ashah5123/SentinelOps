package com.sentinelops.incident.alerts.correlation;

import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.util.List;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Matches when exactly one currently-open incident shares this alert's exact {@code service} and
 * {@code environment} (both required, case-sensitive exact match — no fuzzy matching) among the
 * bounded, time-windowed candidate set {@code AlertProcessingListener} assembles. Strict on
 * purpose: if more than one open incident shares the same service/environment, this rule
 * deliberately declines rather than guessing which one is "more related" (see section 8's
 * over-merging guidance) — an ambiguous case creates a separate incident instead.
 */
@Component
@Order(10)
public class ServiceEnvironmentWindowCorrelationRule implements CorrelationRule {

  @Override
  public String id() {
    return "service-environment-window";
  }

  @Override
  public int version() {
    return 1;
  }

  @Override
  public CorrelationDecision evaluate(CanonicalAlert alert, List<CorrelationCandidate> candidates) {
    if (isBlank(alert.service()) || isBlank(alert.environment())) {
      return CorrelationDecision.noMatch();
    }

    List<CorrelationCandidate> matches =
        candidates.stream()
            .filter(
                c ->
                    alert.service().equals(c.service())
                        && alert.environment().equals(c.environment()))
            .toList();

    if (matches.isEmpty()) {
      return CorrelationDecision.noMatch();
    }
    if (matches.size() > 1) {
      return CorrelationDecision.ambiguous(
          matches.size()
              + " open incidents share service="
              + alert.service()
              + " environment="
              + alert.environment()
              + " — declining to guess which one this alert belongs to.");
    }

    CorrelationCandidate match = matches.get(0);
    return CorrelationDecision.match(
        match.incidentId(),
        id(),
        version(),
        Map.of("service", alert.service(), "environment", alert.environment()),
        "Exactly one open incident shares service="
            + alert.service()
            + " and environment="
            + alert.environment()
            + " within the correlation time window.");
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
