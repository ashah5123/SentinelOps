package com.sentinelops.telemetry.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI telemetryCorrelationServiceOpenApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("SentinelOps Telemetry Correlation Service API")
                .version("v1")
                .description(
                    "Telemetry ingestion, normalization, and deterministic correlation API. "
                        + "SECURITY NOTICE: this phase implements no authentication or "
                        + "authorization. This API uses a local-development security boundary "
                        + "only and must never be exposed on a public or shared network."));
  }
}
