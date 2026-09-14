package com.sentinelops.telemetry.adapters.loki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/** Minimal shape of the Loki HTTP API's {@code /loki/api/v1/query_range} response. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LokiResponse(@JsonProperty("status") String status, @JsonProperty("data") Data data) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Data(@JsonProperty("result") List<Stream> result) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Stream(
      @JsonProperty("stream") Map<String, String> stream,
      // Each entry is [ "<unix-nanosecond timestamp>", "<log line>" ].
      @JsonProperty("values") List<List<String>> values) {}
}
