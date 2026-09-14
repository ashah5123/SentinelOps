package com.sentinelops.telemetry.adapters.tempo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** Minimal shape of Tempo's {@code /api/search} response — one summary row per matched trace. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TempoSearchResponse(@JsonProperty("traces") List<TraceSummary> traces) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TraceSummary(
      @JsonProperty("traceID") String traceId,
      @JsonProperty("rootServiceName") String rootServiceName,
      @JsonProperty("rootTraceName") String rootTraceName,
      @JsonProperty("startTimeUnixNano") String startTimeUnixNano,
      @JsonProperty("durationMs") Long durationMs) {}
}
