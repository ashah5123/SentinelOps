package com.sentinelops.incident.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * Explicit JSON serialization configuration, shared by the REST API and every Kafka-compatible
 * event this service publishes or consumes: timestamps are ISO-8601 (never epoch numbers), and
 * unknown JSON properties are ignored on read so additive, backward-compatible payload changes
 * don't break this service.
 */
@Configuration
public class JacksonConfig {

  @Bean
  public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
    return builder -> {
      builder.modules(new JavaTimeModule());
      builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
      builder.featuresToDisable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    };
  }

  /**
   * Exposed directly for components (outbox writer, idempotency guard, Kafka listener) that need an
   * {@link ObjectMapper} outside the Spring MVC message-conversion pipeline, built from the same
   * auto-configured, customized builder Spring Boot uses everywhere else — so every component
   * shares one consistent JSON configuration.
   */
  @Bean
  public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
    return builder.build();
  }
}
