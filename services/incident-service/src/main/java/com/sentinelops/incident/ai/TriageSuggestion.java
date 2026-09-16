package com.sentinelops.incident.ai;

import java.util.List;

/**
 * The strict structured-output schema every AI provider must produce (see {@code
 * StructuredOutputValidator}). Deliberately a closed record: Jackson is configured to reject any
 * JSON property not listed here (see the validator), so a provider — or content it was tricked into
 * echoing — cannot smuggle an unexpected field through.
 *
 * <p>{@code citations} must only ever reference chunk IDs that were actually retrieved for this
 * request (validated separately); nothing here is treated as authoritative — see
 * docs/development/ai-triage.md's trust-boundary section.
 */
public record TriageSuggestion(
    String summary,
    String suggestedCategory,
    String suggestedSeverity,
    String confidenceStatement,
    List<String> evidence,
    List<String> diagnosticSteps,
    List<String> escalationConditions,
    List<Citation> citations,
    List<String> limitations) {

  public record Citation(String chunkId, String sourceTitle, String section, double score) {}
}
