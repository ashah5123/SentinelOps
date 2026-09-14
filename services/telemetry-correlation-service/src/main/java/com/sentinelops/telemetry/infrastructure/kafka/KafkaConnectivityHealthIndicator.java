package com.sentinelops.telemetry.infrastructure.kafka;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.stereotype.Component;

/**
 * Reports Kafka-compatible broker connectivity as part of {@code /actuator/health}. Used for
 * readiness rather than liveness — a transient broker outage should not cause this process to be
 * killed and restarted, only to be marked temporarily not-ready. Mirrors the incident service's own
 * {@code KafkaConnectivityHealthIndicator}.
 */
@Component
public class KafkaConnectivityHealthIndicator implements HealthIndicator {

  private static final int TIMEOUT_SECONDS = 3;

  private final ConsumerFactory<String, String> consumerFactory;

  public KafkaConnectivityHealthIndicator(ConsumerFactory<String, String> consumerFactory) {
    this.consumerFactory = consumerFactory;
  }

  @Override
  public Health health() {
    Map<String, Object> adminConfig = Map.copyOf(consumerFactory.getConfigurationProperties());
    try (AdminClient adminClient = AdminClient.create(adminConfig)) {
      int nodeCount =
          adminClient.describeCluster().nodes().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).size();
      return Health.up().withDetail("brokerNodeCount", nodeCount).build();
    } catch (Exception e) {
      return Health.down().withDetail("reason", e.getMessage()).build();
    }
  }
}
