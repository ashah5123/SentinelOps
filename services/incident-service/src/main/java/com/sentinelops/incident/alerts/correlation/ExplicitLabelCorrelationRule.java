package com.sentinelops.incident.alerts.correlation;

import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Highest-priority rule: an alert can explicitly name the incident it belongs to via the {@code
 * sentinelops_incident_id} label (section 8's "explicit routing labels"). This is the only rule
 * that does not need a matching candidate already open — the label is authoritative as long as it
 * names a real, still-open incident from the candidate set; otherwise it falls through to the next
 * rule rather than failing the whole ingestion.
 */
@Component
@Order(0)
public class ExplicitLabelCorrelationRule implements CorrelationRule {

  public static final String LABEL_KEY = "sentinelops_incident_id";

  @Override
  public String id() {
    return "explicit-label";
  }

  @Override
  public int version() {
    return 1;
  }

  @Override
  public CorrelationDecision evaluate(CanonicalAlert alert, List<CorrelationCandidate> candidates) {
    if (alert.labels() == null) {
      return CorrelationDecision.noMatch();
    }
    String labeledId = alert.labels().get(LABEL_KEY);
    if (labeledId == null || labeledId.isBlank()) {
      return CorrelationDecision.noMatch();
    }
    UUID targetId;
    try {
      targetId = UUID.fromString(labeledId.trim());
    } catch (IllegalArgumentException e) {
      return CorrelationDecision.noMatch();
    }
    boolean isOpenCandidate = candidates.stream().anyMatch(c -> c.incidentId().equals(targetId));
    if (!isOpenCandidate) {
      return CorrelationDecision.noMatch();
    }
    return CorrelationDecision.match(
        targetId,
        id(),
        version(),
        Map.of(LABEL_KEY, labeledId),
        "Alert explicitly labeled with sentinelops_incident_id="
            + labeledId
            + ", which names a currently open incident.");
  }
}
