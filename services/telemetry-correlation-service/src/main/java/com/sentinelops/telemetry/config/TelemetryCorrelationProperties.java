package com.sentinelops.telemetry.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * SentinelOps telemetry-correlation-service configuration. Every field is required and validated at
 * startup, matching the incident service's fail-fast convention — missing configuration must never
 * silently fall back to an unsafe default.
 */
@ConfigurationProperties(prefix = "sentinelops.telemetry")
@Validated
public class TelemetryCorrelationProperties {

  @NotBlank private final String producerName;

  @NotEmpty private final List<@NotBlank String> monitoredServices;

  private final Backend prometheus;
  private final Backend loki;
  private final Backend tempo;

  @NotEmpty private final Map<@NotBlank String, @NotBlank String> prometheusQueries;

  @NotBlank private final String lokiQuery;
  @NotBlank private final String tempoServiceTag;
  private final Ingestion ingestion;
  private final Correlation correlation;
  private final Outbox outbox;
  private final Consumer consumer;

  public TelemetryCorrelationProperties(
      String producerName,
      List<String> monitoredServices,
      Backend prometheus,
      Backend loki,
      Backend tempo,
      Map<String, String> prometheusQueries,
      String lokiQuery,
      String tempoServiceTag,
      Ingestion ingestion,
      Correlation correlation,
      Outbox outbox,
      Consumer consumer) {
    this.producerName = producerName;
    this.monitoredServices = monitoredServices;
    this.prometheus = prometheus;
    this.loki = loki;
    this.tempo = tempo;
    this.prometheusQueries = prometheusQueries;
    this.lokiQuery = lokiQuery;
    this.tempoServiceTag = tempoServiceTag;
    this.ingestion = ingestion;
    this.correlation = correlation;
    this.outbox = outbox;
    this.consumer = consumer;
  }

  /** Metric label (e.g. "request_rate") to PromQL template, with {@code %s} for the job name. */
  public Map<String, String> prometheusQueries() {
    return prometheusQueries;
  }

  /** LogQL template with {@code %s} for the service label value. */
  public String lokiQuery() {
    return lokiQuery;
  }

  /** The Tempo search tag key used to filter by service (e.g. {@code service.name}). */
  public String tempoServiceTag() {
    return tempoServiceTag;
  }

  public String producerName() {
    return producerName;
  }

  public List<String> monitoredServices() {
    return monitoredServices;
  }

  public Backend prometheus() {
    return prometheus;
  }

  public Backend loki() {
    return loki;
  }

  public Backend tempo() {
    return tempo;
  }

  public Ingestion ingestion() {
    return ingestion;
  }

  public Correlation correlation() {
    return correlation;
  }

  public Outbox outbox() {
    return outbox;
  }

  public Consumer consumer() {
    return consumer;
  }

  /** Connection settings for one telemetry backend (Prometheus, Loki, or Tempo). */
  public static class Backend {
    @NotBlank private final String baseUrl;
    @NotNull private final Duration connectTimeout;
    @NotNull private final Duration readTimeout;

    @Min(0)
    private final int maxRetries;

    @NotNull private final Duration retryBackoff;

    @Min(1)
    private final int maxResponseBytes;

    public Backend(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int maxRetries,
        Duration retryBackoff,
        int maxResponseBytes) {
      this.baseUrl = baseUrl;
      this.connectTimeout = connectTimeout;
      this.readTimeout = readTimeout;
      this.maxRetries = maxRetries;
      this.retryBackoff = retryBackoff;
      this.maxResponseBytes = maxResponseBytes;
    }

    public String baseUrl() {
      return baseUrl;
    }

    public Duration connectTimeout() {
      return connectTimeout;
    }

    public Duration readTimeout() {
      return readTimeout;
    }

    public int maxRetries() {
      return maxRetries;
    }

    public Duration retryBackoff() {
      return retryBackoff;
    }

    public int maxResponseBytes() {
      return maxResponseBytes;
    }
  }

  /** Incremental polling settings shared by all three backend adapters. */
  public static class Ingestion {
    @NotNull private final Duration pollingInterval;
    @NotNull private final Duration queryWindow;
    @NotNull private final Duration overlapWindow;

    @Min(1)
    private final int maxRecordsPerCycle;

    @Min(1)
    private final int batchSize;

    public Ingestion(
        Duration pollingInterval,
        Duration queryWindow,
        Duration overlapWindow,
        int maxRecordsPerCycle,
        int batchSize) {
      this.pollingInterval = pollingInterval;
      this.queryWindow = queryWindow;
      this.overlapWindow = overlapWindow;
      this.maxRecordsPerCycle = maxRecordsPerCycle;
      this.batchSize = batchSize;
    }

    public Duration pollingInterval() {
      return pollingInterval;
    }

    public Duration queryWindow() {
      return queryWindow;
    }

    public Duration overlapWindow() {
      return overlapWindow;
    }

    public int maxRecordsPerCycle() {
      return maxRecordsPerCycle;
    }

    public int batchSize() {
      return batchSize;
    }
  }

  /** Correlation-engine settings: search window, dependency depth, and rule weights. */
  public static class Correlation {
    @NotNull private final Duration window;

    @Min(1)
    private final int maxDependencyDepth;

    @Min(1)
    private final int maxEvidenceResults;

    private final Weights weights;

    public Correlation(
        Duration window, int maxDependencyDepth, int maxEvidenceResults, Weights weights) {
      this.window = window;
      this.maxDependencyDepth = maxDependencyDepth;
      this.maxEvidenceResults = maxEvidenceResults;
      this.weights = weights;
    }

    public Duration window() {
      return window;
    }

    public int maxDependencyDepth() {
      return maxDependencyDepth;
    }

    public int maxEvidenceResults() {
      return maxEvidenceResults;
    }

    public Weights weights() {
      return weights;
    }

    /** Each weight is a non-negative contribution to a piece of evidence's total score. */
    public static class Weights {
      @DecimalMin("0.0")
      @DecimalMax("100.0")
      private final double affectedServiceMatch;

      @DecimalMin("0.0")
      @DecimalMax("100.0")
      private final double timeProximity;

      @DecimalMin("0.0")
      @DecimalMax("100.0")
      private final double traceIdMatch;

      @DecimalMin("0.0")
      @DecimalMax("100.0")
      private final double correlationIdMatch;

      @DecimalMin("0.0")
      @DecimalMax("100.0")
      private final double recentDeployment;

      @DecimalMin("0.0")
      @DecimalMax("100.0")
      private final double dependencyConnection;

      @DecimalMin("0.0")
      @DecimalMax("100.0")
      private final double errorOrFailedStatus;

      @DecimalMin("0.0")
      @DecimalMax("100.0")
      private final double elevatedMetric;

      public Weights(
          double affectedServiceMatch,
          double timeProximity,
          double traceIdMatch,
          double correlationIdMatch,
          double recentDeployment,
          double dependencyConnection,
          double errorOrFailedStatus,
          double elevatedMetric) {
        this.affectedServiceMatch = affectedServiceMatch;
        this.timeProximity = timeProximity;
        this.traceIdMatch = traceIdMatch;
        this.correlationIdMatch = correlationIdMatch;
        this.recentDeployment = recentDeployment;
        this.dependencyConnection = dependencyConnection;
        this.errorOrFailedStatus = errorOrFailedStatus;
        this.elevatedMetric = elevatedMetric;
      }

      public double affectedServiceMatch() {
        return affectedServiceMatch;
      }

      public double timeProximity() {
        return timeProximity;
      }

      public double traceIdMatch() {
        return traceIdMatch;
      }

      public double correlationIdMatch() {
        return correlationIdMatch;
      }

      public double recentDeployment() {
        return recentDeployment;
      }

      public double dependencyConnection() {
        return dependencyConnection;
      }

      public double errorOrFailedStatus() {
        return errorOrFailedStatus;
      }

      public double elevatedMetric() {
        return elevatedMetric;
      }
    }
  }

  /** Transactional outbox publisher settings — same shape as the incident service's own. */
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

  /** Inbound Kafka consumer settings. */
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

    public Duration processedEventRetention() {
      return processedEventRetention;
    }

    public Duration processedEventCleanupInterval() {
      return processedEventCleanupInterval;
    }
  }
}
