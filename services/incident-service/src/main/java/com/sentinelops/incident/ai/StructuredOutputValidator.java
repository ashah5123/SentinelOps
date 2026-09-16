package com.sentinelops.incident.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Strictly validates a provider's raw JSON output against the {@link TriageSuggestion} schema and
 * cross-checks every citation against the chunk IDs actually retrieved for this request — a
 * provider (especially a language model) can only cite evidence it was actually given, never
 * fabricate a plausible-looking source. This is the pipeline's primary defense against both
 * hallucinated citations and prompt-injection attempts that try to smuggle extra instructions or
 * fields into the output.
 */
@Component
public class StructuredOutputValidator {

  /**
   * Reject unknown fields so a provider cannot smuggle extra data (e.g. injected instructions)
   * through.
   */
  private final ObjectMapper strictMapper;

  public StructuredOutputValidator(ObjectMapper baseMapper) {
    this.strictMapper =
        baseMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
  }

  public sealed interface ValidationResult permits Valid, Invalid {}

  public record Valid(TriageSuggestion suggestion) implements ValidationResult {}

  public record Invalid(String reason) implements ValidationResult {}

  public ValidationResult validate(String rawOutput, Set<String> retrievedChunkIds) {
    TriageSuggestion suggestion;
    try {
      suggestion = strictMapper.readValue(rawOutput, TriageSuggestion.class);
    } catch (Exception e) {
      return new Invalid("Output did not parse as valid TriageSuggestion JSON: " + e.getMessage());
    }

    if (suggestion.summary() == null || suggestion.summary().isBlank()) {
      return new Invalid("summary must not be blank");
    }
    if (suggestion.summary().length() > 2000) {
      return new Invalid("summary exceeds maximum length");
    }
    if (suggestion.confidenceStatement() == null || suggestion.confidenceStatement().isBlank()) {
      return new Invalid("confidenceStatement must not be blank");
    }
    if (suggestion.citations() == null) {
      return new Invalid("citations must not be null (use an empty list)");
    }
    if (suggestion.diagnosticSteps() == null || suggestion.diagnosticSteps().size() > 15) {
      return new Invalid("diagnosticSteps must be present and bounded to 15 entries");
    }
    if (suggestion.evidence() == null) {
      return new Invalid("evidence must not be null (use an empty list)");
    }
    if (suggestion.limitations() == null) {
      return new Invalid("limitations must not be null (use an empty list)");
    }

    for (TriageSuggestion.Citation citation : suggestion.citations()) {
      if (citation.chunkId() == null || !retrievedChunkIds.contains(citation.chunkId())) {
        return new Invalid(
            "citation references chunkId '"
                + citation.chunkId()
                + "' which was not among the "
                + "passages actually retrieved for this request");
      }
    }

    return new Valid(suggestion);
  }
}
