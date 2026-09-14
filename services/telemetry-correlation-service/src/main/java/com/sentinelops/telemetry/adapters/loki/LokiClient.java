package com.sentinelops.telemetry.adapters.loki;

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
 * Queries the Loki HTTP API for one configured LogQL expression over one time window. Wrapped in a
 * bounded retry and circuit breaker (the "loki" Resilience4j instances) so a temporarily
 * unreachable Loki never blocks Prometheus or Tempo ingestion — see the ingestion scheduler's
 * per-source isolation. Never forwards credentials or authorization headers to storage; only the
 * response body is read, and only bounded fields from it are kept (see {@link LokiNormalizer}).
 */
@Component
public class LokiClient {

  private static final Logger log = LoggerFactory.getLogger(LokiClient.class);
  private static final String INSTANCE_NAME = "loki";
  private static final int DEFAULT_LIMIT = 500;

  private final RestClient lokiRestClient;
  private final ObjectMapper objectMapper;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;
  private final int maxResponseBytes;

  public LokiClient(
      RestClient lokiRestClient,
      ObjectMapper objectMapper,
      CircuitBreakerRegistry circuitBreakerRegistry,
      RetryRegistry retryRegistry,
      TelemetryCorrelationProperties properties) {
    this.lokiRestClient = lokiRestClient;
    this.objectMapper = objectMapper;
    this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(INSTANCE_NAME);
    this.retry = retryRegistry.retry(INSTANCE_NAME);
    this.maxResponseBytes = properties.loki().maxResponseBytes();
  }

  public LokiResponse queryRange(String logQlQuery, Instant from, Instant to) {
    Supplier<LokiResponse> call =
        () -> {
          try {
            String body =
                lokiRestClient
                    .get()
                    .uri(
                        uriBuilder ->
                            uriBuilder
                                .path("/loki/api/v1/query_range")
                                .queryParam("query", logQlQuery)
                                .queryParam("start", from.getEpochSecond() + "000000000")
                                .queryParam("end", to.getEpochSecond() + "000000000")
                                .queryParam("limit", DEFAULT_LIMIT)
                                .queryParam("direction", "forward")
                                .build())
                    .retrieve()
                    .body(String.class);
            if (body != null && body.length() > maxResponseBytes) {
              throw new LokiQueryException(
                  "Loki response exceeded max-response-bytes (" + maxResponseBytes + ")");
            }
            return deserialize(body);
          } catch (org.springframework.web.client.RestClientException e) {
            throw new LokiQueryException("Loki query failed: " + e.getMessage(), e);
          }
        };

    Supplier<LokiResponse> decorated =
        CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, call));
    try {
      return decorated.get();
    } catch (Exception e) {
      log.warn("Loki query failed after retries/circuit-breaker: {}", e.getMessage());
      throw (e instanceof LokiQueryException lqe)
          ? lqe
          : new LokiQueryException("Loki query failed: " + e.getMessage(), e);
    }
  }

  private LokiResponse deserialize(String body) {
    try {
      return objectMapper.readValue(body, LokiResponse.class);
    } catch (Exception e) {
      throw new LokiQueryException("Failed to parse Loki response: " + e.getMessage(), e);
    }
  }
}
