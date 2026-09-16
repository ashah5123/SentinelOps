package com.sentinelops.incident.ai;

import com.sentinelops.incident.ai.provider.TriageContext;
import com.sentinelops.incident.application.TimelineEntry;
import com.sentinelops.incident.domain.Incident;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Extracts only the incident fields safe to send to an AI provider (never the assignee ID,
 * correlation ID, or any audit actor identity — see docs/development/ai-triage.md's data-handling
 * section), and bounds free-text length so a maliciously huge description cannot inflate the prompt
 * or defeat the length limits enforced elsewhere in the pipeline.
 */
@Component
public class RedactionService {

  private final AiProperties properties;

  public RedactionService(AiProperties properties) {
    this.properties = properties;
  }

  public TriageContext buildContext(
      Incident incident,
      List<TimelineEntry> timeline,
      List<TriageContext.RetrievedPassage> passages) {
    return new TriageContext(
        truncate(incident.getTitle(), 200),
        truncate(incident.getDescription(), properties.prompt().maxDescriptionChars()),
        incident.getSeverity().name(),
        incident.getStatus().name(),
        incident.getAffectedService(),
        incident.getSource(),
        timeline.stream().map(entry -> truncate(entry.summary(), 300)).limit(10).toList(),
        passages);
  }

  /** The free-text search query built from the same safe fields, used for retrieval. */
  public String buildRetrievalQuery(Incident incident) {
    return String.join(
        " ",
        nullToEmpty(incident.getTitle()),
        nullToEmpty(incident.getDescription()),
        nullToEmpty(incident.getAffectedService()));
  }

  private String truncate(String value, int maxLength) {
    if (value == null) {
      return "";
    }
    return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
  }

  private String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
