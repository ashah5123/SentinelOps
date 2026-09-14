package com.sentinelops.telemetry.adapters.loki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Converts raw Loki log lines (JSON, per the Phase 4 structured-logging format) into evidence-ready
 * builders.
 *
 * <p>Only a fixed, bounded set of fields is ever extracted and stored — never the raw log payload,
 * request bodies, or authorization/credential-shaped content. {@link #SECRET_PATTERN} is a
 * defense-in-depth redaction applied to whatever summary text is kept, in case a message happens to
 * embed something header- or credential-shaped. Trace and correlation IDs are stored as regular
 * columns on {@link Evidence}, never as Loki query labels — see {@code
 * docs/development/observability.md}.
 */
@Component
public class LokiNormalizer {

  private static final int MAX_SUMMARY_LENGTH = 500;
  private static final int MAX_LINE_LENGTH = 4096;
  private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}&&[^\\r\\n\\t]]");
  private static final Pattern SECRET_PATTERN =
      Pattern.compile("(?i)(authorization|api[_-]?key|password|secret|token)\\s*[:=]\\s*.+");

  private final ObjectMapper objectMapper;

  public LokiNormalizer(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  public List<Evidence.Builder> normalize(
      String lokiQuery, String monitoredService, LokiResponse response) {
    List<Evidence.Builder> results = new ArrayList<>();
    if (response == null || response.data() == null || response.data().result() == null) {
      return results;
    }
    for (LokiResponse.Stream stream : response.data().result()) {
      if (stream.values() == null) {
        continue;
      }
      for (List<String> entry : stream.values()) {
        toEvidence(lokiQuery, monitoredService, entry).ifPresent(results::add);
      }
    }
    return results;
  }

  private java.util.Optional<Evidence.Builder> toEvidence(
      String lokiQuery, String monitoredService, List<String> entry) {
    if (entry == null || entry.size() != 2) {
      return java.util.Optional.empty();
    }
    Instant observedAt;
    try {
      observedAt = Instant.ofEpochSecond(0, Long.parseLong(entry.get(0)));
    } catch (NumberFormatException e) {
      return java.util.Optional.empty();
    }

    String rawLine = entry.get(1);
    if (rawLine == null || rawLine.isBlank()) {
      return java.util.Optional.empty();
    }
    if (rawLine.length() > MAX_LINE_LENGTH) {
      rawLine = rawLine.substring(0, MAX_LINE_LENGTH);
    }

    Map<String, Object> parsed = parseJson(rawLine);
    String level = stringField(parsed, "log.level", "level");
    String traceId = stringField(parsed, "trace.id", "traceId");
    String correlationId = stringField(parsed, "correlationId", "correlation_id");
    String eventType = stringField(parsed, "event.type", "eventType");
    String errorCode = stringField(parsed, "error.code", "errorCode");
    String message = stringField(parsed, "message");
    if (message == null) {
      message = rawLine;
    }

    String summary = sanitize(message);

    Map<String, String> attributes = new LinkedHashMap<>();
    if (level != null) {
      attributes.put("level", level);
    }
    if (eventType != null) {
      attributes.put("eventType", sanitize(eventType));
    }
    if (errorCode != null) {
      attributes.put("errorCode", sanitize(errorCode));
    }

    Evidence.Builder builder =
        Evidence.builder(EvidenceType.LOG, SourceSystem.LOKI, monitoredService)
            .observedAt(observedAt)
            .traceId(traceId)
            .correlationId(correlationId)
            .severity(level)
            .summary(summary)
            .sourceReference("loki:query_range?query=" + lokiQuery)
            .attributes(attributes);
    return java.util.Optional.of(builder);
  }

  private Map<String, Object> parseJson(String rawLine) {
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = objectMapper.readValue(rawLine, Map.class);
      return parsed;
    } catch (Exception e) {
      return Map.of();
    }
  }

  private String stringField(Map<String, Object> parsed, String... candidateKeys) {
    for (String key : candidateKeys) {
      Object value = parsed.get(key);
      if (value != null) {
        return String.valueOf(value);
      }
    }
    return null;
  }

  private String sanitize(String value) {
    String withoutControlChars = CONTROL_CHARS.matcher(value).replaceAll(" ");
    String redacted = SECRET_PATTERN.matcher(withoutControlChars).replaceAll("$1=[REDACTED]");
    return redacted.length() <= MAX_SUMMARY_LENGTH
        ? redacted
        : redacted.substring(0, MAX_SUMMARY_LENGTH);
  }
}
