package com.sentinelops.telemetry.adapters.tempo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.time.Instant;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Queries the Tempo HTTP API: {@link #search} for the periodic-polling path (bounded number of
 * recent traces for a service) and {@link #getTrace} for on-demand, span-level lookup by trace ID
 * (e.g. a trace ID found in a Loki log line). Wrapped in a bounded retry and circuit breaker (the
 * "tempo" Resilience4j instances) so a temporarily unreachable Tempo never blocks Prometheus or
 * Loki ingestion.
 */
@Component
public class TempoClient {

  private static final Logger log = LoggerFactory.getLogger(TempoClient.class);
  private static final String INSTANCE_NAME = "tempo";
  private static final int DEFAULT_SEARCH_LIMIT = 50;

  private final RestClient tempoRestClient;
  private final ObjectMapper objectMapper;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  private final int maxResponseBytes;
  private final String serviceTag;

  public TempoClient(
      RestClient tempoRestClient,
      ObjectMapper objectMapper,
      CircuitBreakerRegistry circuitBreakerRegistry,
      RetryRegistry retryRegistry,
      TelemetryCorrelationProperties properties) {
    this.tempoRestClient = tempoRestClient;
    this.objectMapper = objectMapper;
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
    this.retry = retryRegistry.retry(INSTANCE_NAME);
    this.maxResponseBytes = properties.tempo().maxResponseBytes();
    this.serviceTag = properties.tempoServiceTag();
  }

  public TempoSearchResponse search(String monitoredService, Instant from, Instant to) {
    return execute(
        () ->
            tempoRestClient
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/api/search")
                            .queryParam("tags", serviceTag + "=" + monitoredService)
                            .queryParam("start", from.getEpochSecond())
                            .queryParam("end", to.getEpochSecond())
                            .queryParam("limit", DEFAULT_SEARCH_LIMIT)
                            .build())
                .retrieve()
                .body(String.class),
        body -> deserialize(body, TempoSearchResponse.class));
  }

  public TempoTraceResponse getTrace(String traceId) {
    return execute(
        () ->
            tempoRestClient
                .get()
                .uri("/api/traces/{traceId}", traceId)
                .retrieve()
                .body(String.class),
        body -> deserialize(body, TempoTraceResponse.class));
  }

  private <T> T execute(Supplier<String> httpCall, java.util.function.Function<String, T> parse) {
    Supplier<T> call =
        () -> {
          try {
            String body = httpCall.get();
            if (body != null && body.length() > maxResponseBytes) {
              throw new TempoQueryException(
                  "Tempo response exceeded max-response-bytes (" + maxResponseBytes + ")");
            }
            return parse.apply(body);
          } catch (org.springframework.web.client.RestClientException e) {
            throw new TempoQueryException("Tempo query failed: " + e.getMessage(), e);
          }
        };
    Supplier<T> decorated =
        CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, call));
    try {
      return decorated.get();
    } catch (Exception e) {
      log.warn("Tempo query failed after retries/circuit-breaker: {}", e.getMessage());
      throw (e instanceof TempoQueryException tqe)
          ? tqe
          : new TempoQueryException("Tempo query failed: " + e.getMessage(), e);
    }
  }

  private <T> T deserialize(String body, Class<T> type) {
    try {
      return objectMapper.readValue(body, type);
    } catch (Exception e) {
      throw new TempoQueryException("Failed to parse Tempo response: " + e.getMessage(), e);
    }
  }
}
