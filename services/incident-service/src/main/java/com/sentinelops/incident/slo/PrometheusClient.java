package com.sentinelops.incident.slo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Queries a Prometheus instant vector and returns its single scalar value, if any. Never throws on
 * an unreachable Prometheus or an empty result — returns {@link Optional#empty()} so callers (see
 * {@link SloEvaluationService}) can degrade gracefully to an UNKNOWN status rather than fail the
 * whole SLO report (section: "The platform must degrade gracefully").
 */
@Component
public class PrometheusClient {

  private static final Logger log = LoggerFactory.getLogger(PrometheusClient.class);

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;

  public PrometheusClient(ObjectMapper objectMapper) {
    this(objectMapper, System.getenv().getOrDefault("PROMETHEUS_URL", "http://localhost:9090"));
  }

  PrometheusClient(ObjectMapper objectMapper, String baseUrl) {
    this.objectMapper = objectMapper;
    this.baseUrl = baseUrl;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  public Optional<Double> queryInstantValue(String promQl) {
    try {
      String encoded = URLEncoder.encode(promQl, StandardCharsets.UTF_8);
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create(baseUrl + "/api/v1/query?query=" + encoded))
              .timeout(Duration.ofSeconds(5))
              .GET()
              .build();
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        log.warn("Prometheus query returned HTTP {}: {}", response.statusCode(), promQl);
        return Optional.empty();
      }
      return parseInstantQueryResponse(response.body());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      log.warn("Prometheus query failed (treated as UNKNOWN, not an error): {}", promQl, e);
      return Optional.empty();
    }
  }

  /**
   * Pure parsing logic, split out from the HTTP call so it is testable without a live Prometheus.
   */
  Optional<Double> parseInstantQueryResponse(String responseBody) {
    try {
      JsonNode root = objectMapper.readTree(responseBody);
      if (!"success".equals(root.path("status").asText())) {
        return Optional.empty();
      }
      JsonNode result = root.path("data").path("result");
      if (!result.isArray() || result.isEmpty()) {
        return Optional.empty();
      }
      JsonNode value = result.get(0).path("value");
      if (!value.isArray() || value.size() < 2) {
        return Optional.empty();
      }
      return Optional.of(Double.parseDouble(value.get(1).asText()));
    } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
      log.warn("Could not parse Prometheus response", e);
      return Optional.empty();
    }
  }
}
