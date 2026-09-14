package com.sentinelops.incident.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
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
    @NotNull private final Duration leaseDuration;
    @NotNull private final Duration publishTimeout;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private final double backoffJitter;

    public Outbox(
        Duration pollingInterval,
        int batchSize,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration retentionAfterPublish,
        Duration leaseDuration,
        Duration publishTimeout,
        double backoffJitter) {
      this.pollingInterval = pollingInterval;
      this.batchSize = batchSize;
      this.maxAttempts = maxAttempts;
      this.initialBackoff = initialBackoff;
      this.maxBackoff = maxBackoff;
      this.retentionAfterPublish = retentionAfterPublish;
      this.leaseDuration = leaseDuration;
      this.publishTimeout = publishTimeout;
      this.backoffJitter = backoffJitter;
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

    /** How long a claimed row is protected from re-claiming by another publisher instance. */
    public Duration leaseDuration() {
      return leaseDuration;
    }

    /** Maximum time to wait for a single Kafka send to complete. */
    public Duration publishTimeout() {
      return publishTimeout;
    }

    /** Maximum fractional jitter applied to the computed backoff (0.0 disables jitter). */
    public double backoffJitter() {
      return backoffJitter;
    }
  }

  /** Inbound anomaly-consumer settings. */
  public static class Consumer {
    @Min(1)
    private final int maxRetries;

    @NotNull private final Duration retryInitialInterval;
    @NotNull private final Duration retryMaxInterval;

    @DecimalMin("1.0")
    private final double retryMultiplier;

    @DecimalMin("0.0")
    @DecimalMax("1.0")
    private final double retryJitter;

    @NotNull private final Duration processedEventRetention;
    @NotNull private final Duration processedEventCleanupInterval;

    public Consumer(
        int maxRetries,
        Duration retryInitialInterval,
        Duration retryMaxInterval,
        double retryMultiplier,
        double retryJitter,
        Duration processedEventRetention,
        Duration processedEventCleanupInterval) {
      this.maxRetries = maxRetries;
      this.retryInitialInterval = retryInitialInterval;
      this.retryMaxInterval = retryMaxInterval;
      this.retryMultiplier = retryMultiplier;
      this.retryJitter = retryJitter;
      this.processedEventRetention = processedEventRetention;
      this.processedEventCleanupInterval = processedEventCleanupInterval;
    }

    public int maxRetries() {
      return maxRetries;
    }

    public Duration retryInitialInterval() {
      return retryInitialInterval;
    }

    public Duration retryMaxInterval() {
      return retryMaxInterval;
    }

    public double retryMultiplier() {
      return retryMultiplier;
    }

    public double retryJitter() {
      return retryJitter;
    }

    /** How long a processed-event idempotency record is kept before cleanup. */
    public Duration processedEventRetention() {
      return processedEventRetention;
    }

    /** How often the processed-event cleanup job runs. */
    public Duration processedEventCleanupInterval() {
      return processedEventCleanupInterval;
    }
  }
}
