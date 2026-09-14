package com.sentinelops.telemetry;

import com.sentinelops.telemetry.config.TelemetryCorrelationProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(TelemetryCorrelationProperties.class)
@EnableScheduling
public class TelemetryCorrelationServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(TelemetryCorrelationServiceApplication.class, args);
  }
}
