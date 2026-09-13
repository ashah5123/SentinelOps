package com.sentinelops.incident.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SentinelOps incident-service configuration. Every field is required and validated at startup —
 * missing required configuration fails fast rather than falling back to an implicit, potentially
 * unsafe default.
 */
@ConfigurationProperties(prefix = "sentinelops.incident-service")
@Validated
public class IncidentServiceProperties {

  @NotBlank private final String producerName;
  private final Outbox outbox;
  private final Consumer consumer;

  public IncidentServiceProperties(String producerName, Outbox outbox, Consumer consumer) {
    this.producerName = producerName;
    this.outbox = outbox;
    this.consumer = consumer;
  }

  public String producerName() {
    return producerName;
  }

  public Outbox outbox() {
    return outbox;
  }

  public Consumer consumer() {
    return consumer;
  }

  /** Transactional outbox publisher settings. */
  public static class Outbox {
    @NotNull private final Duration pollingInterval;

    @Min(1)
    private final int batchSize;

    @Min(1)
    private final int maxAttempts;

    @NotNull private final Duration initialBackoff;
    @NotNull private final Duration maxBackoff;
    @NotNull private final Duration retentionAfterPublish;

    public Outbox(
        Duration pollingInterval,
        int batchSize,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration retentionAfterPublish) {
      this.pollingInterval = pollingInterval;
      this.batchSize = batchSize;
      this.maxAttempts = maxAttempts;
      this.initialBackoff = initialBackoff;
      this.maxBackoff = maxBackoff;
      this.retentionAfterPublish = retentionAfterPublish;
    }

    public Duration pollingInterval() {
      return pollingInterval;
    }

    public int batchSize() {
      return batchSize;
    }

    public int maxAttempts() {
      return maxAttempts;
    }

    public Duration initialBackoff() {
      return initialBackoff;
    }

    public Duration maxBackoff() {
      return maxBackoff;
    }

    public Duration retentionAfterPublish() {
      return retentionAfterPublish;
    }
  }

  /** Inbound anomaly-consumer settings. */
  public static class Consumer {
    @Min(1)
    private final int maxRetries;

    public Consumer(int maxRetries) {
      this.maxRetries = maxRetries;
    }

    public int maxRetries() {
      return maxRetries;
    }
  }
}
