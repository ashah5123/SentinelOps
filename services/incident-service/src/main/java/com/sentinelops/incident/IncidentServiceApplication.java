package com.sentinelops.incident;

import com.sentinelops.incident.config.IncidentServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(IncidentServiceProperties.class)
@EnableScheduling
public class IncidentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(IncidentServiceApplication.class, args);
  }
}
