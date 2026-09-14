package com.sentinelops.telemetry.adapters.tempo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Minimal shape of Tempo's {@code /api/traces/{traceID}} response — OTLP-JSON, one entry of
 * resource spans per instrumented service.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TempoTraceResponse(@JsonProperty("batches") List<ResourceSpans> batches) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ResourceSpans(
      @JsonProperty("resource") Resource resource,
      @JsonProperty("scopeSpans") List<ScopeSpans> scopeSpans) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Resource(@JsonProperty("attributes") List<KeyValue> attributes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record ScopeSpans(@JsonProperty("spans") List<Span> spans) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Span(
      @JsonProperty("traceId") String traceId,
      @JsonProperty("spanId") String spanId,
      @JsonProperty("parentSpanId") String parentSpanId,
      @JsonProperty("name") String name,
      @JsonProperty("startTimeUnixNano") String startTimeUnixNano,
      @JsonProperty("endTimeUnixNano") String endTimeUnixNano,
      @JsonProperty("status") Status status) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Status(@JsonProperty("code") String code) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record KeyValue(@JsonProperty("key") String key, @JsonProperty("value") AnyValue value) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record AnyValue(@JsonProperty("stringValue") String stringValue) {}
}
