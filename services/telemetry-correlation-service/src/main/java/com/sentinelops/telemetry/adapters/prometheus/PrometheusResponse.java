package com.sentinelops.telemetry.adapters.prometheus;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Minimal shape of the Prometheus HTTP API's {@code /api/v1/query_range} response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PrometheusResponse(
    @JsonProperty("status") String status, @JsonProperty("data") Data data) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(
      @JsonProperty("resultType") String resultType, @JsonProperty("result") List<Result> result) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Result(
      @JsonProperty("metric") Map<String, String> metric,
      // Present for a "matrix" resultType (query_range): a list of [timestamp, value] pairs.
      @JsonProperty("values") List<List<Object>> values) {}
}
