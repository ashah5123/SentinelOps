package com.sentinelops.telemetry.adapters.tempo;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Converts Tempo API responses into evidence-ready builders.
 *
 * <p>{@link #normalizeSearchResults} handles the periodic-polling path (one coarse, root-trace
 * record per matched trace, bounding volume per cycle). {@link #normalizeTrace} handles the
 * on-demand, span-level lookup path (triggered by a trace ID found in Loki logs, or by the
 * correlation engine) and preserves trace ID, span ID, parent span ID, service, operation,
 * duration, status, and timestamp for every span — but never arbitrary span attributes beyond that
 * fixed set, to keep stored evidence bounded and non-sensitive.
 */
@Component
public class TempoNormalizer {

  public List<Evidence.Builder> normalizeSearchResults(
      String monitoredService, TempoSearchResponse response) {
    List<Evidence.Builder> results = new ArrayList<>();
    if (response == null || response.traces() == null) {
      return results;
    }
    for (TempoSearchResponse.TraceSummary trace : response.traces()) {
      if (trace.traceId() == null || trace.startTimeUnixNano() == null) {
        continue;
      }
      Instant observedAt = fromUnixNanoString(trace.startTimeUnixNano());
      if (observedAt == null) {
        continue;
      }
      Map<String, String> attributes = new LinkedHashMap<>();
      if (trace.durationMs() != null) {
        attributes.put("durationMs", String.valueOf(trace.durationMs()));
      }
      String operation = trace.rootTraceName() != null ? trace.rootTraceName() : "unknown";
      Evidence.Builder builder =
          Evidence.builder(EvidenceType.TRACE, SourceSystem.TEMPO, monitoredService)
              .observedAt(observedAt)
              .traceId(trace.traceId())
              .summary(truncate("trace " + operation, 500))
              .sourceReference("tempo:trace/" + trace.traceId())
              .attributes(attributes);
      results.add(builder);
    }
    return results;
  }

  public List<Evidence.Builder> normalizeTrace(
      String fallbackService, String traceId, TempoTraceResponse response) {
    List<Evidence.Builder> results = new ArrayList<>();
    if (response == null || response.batches() == null) {
      return results;
    }
    for (TempoTraceResponse.ResourceSpans batch : response.batches()) {
      String service = resourceServiceName(batch.resource()).orElse(fallbackService);
      if (batch.scopeSpans() == null) {
        continue;
      }
      for (TempoTraceResponse.ScopeSpans scopeSpans : batch.scopeSpans()) {
        if (scopeSpans.spans() == null) {
          continue;
        }
        for (TempoTraceResponse.Span span : scopeSpans.spans()) {
          toEvidence(service, traceId, span).ifPresent(results::add);
        }
      }
    }
    return results;
  }

  private java.util.Optional<Evidence.Builder> toEvidence(
      String service, String traceId, TempoTraceResponse.Span span) {
    Instant observedAt = fromUnixNanoString(span.startTimeUnixNano());
    if (observedAt == null) {
      return java.util.Optional.empty();
    }
    long durationNanos = 0;
    try {
      if (span.startTimeUnixNano() != null && span.endTimeUnixNano() != null) {
        durationNanos =
            Long.parseLong(span.endTimeUnixNano()) - Long.parseLong(span.startTimeUnixNano());
      }
    } catch (NumberFormatException ignored) {
      // leave durationNanos at 0 rather than reject the whole span
    }
    String status = span.status() != null ? span.status().code() : null;
    String operation = span.name() != null ? span.name() : "unknown";

    Map<String, String> attributes = new LinkedHashMap<>();
    if (span.parentSpanId() != null && !span.parentSpanId().isBlank()) {
      attributes.put("parentSpanId", span.parentSpanId());
    }
    attributes.put("operation", truncate(operation, 200));
    attributes.put("durationNanos", String.valueOf(durationNanos));

    Evidence.Builder builder =
        Evidence.builder(EvidenceType.TRACE, SourceSystem.TEMPO, service)
            .observedAt(observedAt)
            .traceId(traceId)
            .spanId(span.spanId())
            .severity(status)
            .summary(truncate(service + " " + operation, 500))
            .sourceReference("tempo:trace/" + traceId + "/span/" + span.spanId())
            .attributes(attributes);
    return java.util.Optional.of(builder);
  }

  private java.util.Optional<String> resourceServiceName(TempoTraceResponse.Resource resource) {
    if (resource == null || resource.attributes() == null) {
      return java.util.Optional.empty();
    }
    return resource.attributes().stream()
        .filter(kv -> "service.name".equals(kv.key()) && kv.value() != null)
        .map(kv -> kv.value().stringValue())
        .filter(java.util.Objects::nonNull)
        .findFirst();
  }

  private Instant fromUnixNanoString(String unixNano) {
    try {
      long nanos = Long.parseLong(unixNano);
      return Instant.ofEpochSecond(0, nanos);
    } catch (NumberFormatException | NullPointerException e) {
      return null;
    }
  }

  private String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
