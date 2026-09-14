package com.sentinelops.telemetry.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * One {@link RestClient} per telemetry backend (Prometheus, Loki, Tempo), each with its own
 * configurable connect/read timeouts (see {@link TelemetryCorrelationProperties.Backend}). Bounded
 * retries and circuit breakers are applied by the adapters themselves via Resilience4j (see {@code
 * resilience4j.retry}/{@code resilience4j.circuitbreaker} instances named "prometheus", "loki",
 * "tempo" in application.yml) rather than in the HTTP client itself, so failure classification
 * (timeout vs. 4xx vs. 5xx) stays visible to the adapter.
 */
@Configuration
public class BackendClientsConfig {

  @Bean
  public RestClient prometheusRestClient(TelemetryCorrelationProperties properties) {
    return buildClient(properties.prometheus());
  }

  @Bean
  public RestClient lokiRestClient(TelemetryCorrelationProperties properties) {
    return buildClient(properties.loki());
  }

  @Bean
  public RestClient tempoRestClient(TelemetryCorrelationProperties properties) {
    return buildClient(properties.tempo());
  }

  private RestClient buildClient(TelemetryCorrelationProperties.Backend backend) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(backend.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(backend.readTimeout());
    return RestClient.builder().baseUrl(backend.baseUrl()).requestFactory(requestFactory).build();
  }
}
