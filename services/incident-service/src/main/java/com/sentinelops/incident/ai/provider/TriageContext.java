package com.sentinelops.incident.ai.provider;

import java.util.List;

/**
 * Everything an {@link AiProvider} is given to work with — already redacted/truncated by {@code
 * RedactionService} before this is built. Deliberately excludes actor/assignee IDs and the raw
 * correlation ID; only the fields a responder would need to understand the incident are included.
 * Every field here is untrusted, attacker-influenceable text (the incident title/ description are
 * free text any authenticated RESPONDER can set) — see docs/development/ai-triage.md's
 * trust-boundary section for how each provider must treat it.
 */
public record TriageContext(
    String incidentTitle,
    String incidentDescription,
    String severity,
    String status,
    String affectedService,
    String source,
    List<String> timelineSummaries,
    List<RetrievedPassage> passages) {

  public record RetrievedPassage(
      String chunkId, String sourceTitle, String section, String content, double score) {}
}
