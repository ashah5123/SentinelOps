package com.sentinelops.telemetry.adapters.prometheus;

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
 * Queries the Prometheus HTTP API for one configured PromQL expression over one time window.
 *
 * <p>Wrapped in a bounded retry (exponential backoff) and a circuit breaker (see the "prometheus"
 * Resilience4j instances in application.yml), so a temporarily unreachable Prometheus never blocks
 * or crashes the ingestion cycle for the other backends — see {@code PrometheusQueryException} and
 * the ingestion scheduler's per-source isolation.
 */
@Component
public class PrometheusClient {

  private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);
  private static final String INSTANCE_NAME = "prometheus";

  private final RestClient prometheusRestClient;
  private final ObjectMapper objectMapper;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  private final int maxResponseBytes;

  public PrometheusClient(
      RestClient prometheusRestClient,
      ObjectMapper objectMapper,
      CircuitBreakerRegistry circuitBreakerRegistry,
      RetryRegistry retryRegistry,
      TelemetryCorrelationProperties properties) {
    this.prometheusRestClient = prometheusRestClient;
    this.objectMapper = objectMapper;
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
    this.retry = retryRegistry.retry(INSTANCE_NAME);
    this.maxResponseBytes = properties.prometheus().maxResponseBytes();
  }

  /**
   * Runs {@code promQuery} as a {@code query_range} request from {@code from} to {@code to}, at a
   * fixed 15s step. Returns an empty response (never throws) is not attempted here — a genuine
   * backend failure propagates as {@link PrometheusQueryException} so the caller can decide how to
   * treat it (see ingestion isolation requirements).
   */
  public PrometheusResponse queryRange(String promQuery, Instant from, Instant to) {
    Supplier<PrometheusResponse> call =
        () -> {
          try {
            String body =
                prometheusRestClient
                    .get()
                    .uri(
                        uriBuilder ->
                            uriBuilder
                                .path("/api/v1/query_range")
                                .queryParam("query", promQuery)
                                .queryParam("start", from.getEpochSecond())
                                .queryParam("end", to.getEpochSecond())
                                .queryParam("step", "15s")
                                .build())
                    .retrieve()
                    .body(String.class);
            if (body != null && body.length() > maxResponseBytes) {
              throw new PrometheusQueryException(
                  "Prometheus response exceeded max-response-bytes (" + maxResponseBytes + ")");
            }
            return deserialize(body);
          } catch (org.springframework.web.client.RestClientException e) {
            throw new PrometheusQueryException("Prometheus query failed: " + e.getMessage(), e);
          }
        };

    Supplier<PrometheusResponse> decorated =
        CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, call));
    try {
      return decorated.get();
    } catch (Exception e) {
      log.warn("Prometheus query failed after retries/circuit-breaker: {}", e.getMessage());
      throw (e instanceof PrometheusQueryException pqe)
          ? pqe
          : new PrometheusQueryException("Prometheus query failed: " + e.getMessage(), e);
    }
  }

  private PrometheusResponse deserialize(String body) {
    try {
      return objectMapper.readValue(body, PrometheusResponse.class);
    } catch (Exception e) {
      throw new PrometheusQueryException(
          "Failed to parse Prometheus response: " + e.getMessage(), e);
    }
  }
}
