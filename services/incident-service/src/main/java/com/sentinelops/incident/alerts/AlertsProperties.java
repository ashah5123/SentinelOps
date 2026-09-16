package com.sentinelops.incident.alerts;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 12 alert-ingestion, correlation, routing, notification, and escalation configuration.
 * Follows the same constructor-bound, validated {@code @ConfigurationProperties} pattern as {@code
 * AiProperties} (Phase 11) and {@code IncidentServiceProperties}.
 */
@ConfigurationProperties(prefix = "sentinelops.alerts")
@Validated
public class AlertsProperties {

  @NotNull private final Ingestion ingestion;
  @NotNull private final Hmac hmac;
  @NotNull private final Alertmanager alertmanager;
  @NotNull private final RateLimit rateLimit;
  @NotNull private final Correlation correlation;
  @NotNull private final Notification notification;
  @NotNull private final Escalation escalation;
  @NotNull private final Routing routing;

  public AlertsProperties(
      Ingestion ingestion,
      Hmac hmac,
      Alertmanager alertmanager,
      RateLimit rateLimit,
      Correlation correlation,
      Notification notification,
      Escalation escalation,
      Routing routing) {
    this.ingestion = ingestion;
    this.hmac = hmac;
    this.alertmanager = alertmanager;
    this.rateLimit = rateLimit;
    this.correlation = correlation;
    this.notification = notification;
    this.escalation = escalation;
    this.routing = routing;
  }

  public Correlation correlation() {
    return correlation;
  }

  public Ingestion ingestion() {
    return ingestion;
  }

  public Hmac hmac() {
    return hmac;
  }

  public Alertmanager alertmanager() {
    return alertmanager;
  }

  public RateLimit rateLimit() {
    return rateLimit;
  }

  public Notification notification() {
    return notification;
  }

  public Escalation escalation() {
    return escalation;
  }

  public Routing routing() {
    return routing;
  }

  /** Canonical-alert validation bounds (section 2). */
  public static class Ingestion {
    @Min(1)
    private final int maxLabels;

    @Min(1)
    private final int maxAnnotations;

    @Min(1)
    private final int maxLabelKeyLength;

    @Min(1)
    private final int maxLabelValueLength;

    @Min(1)
    private final int maxAlertNameLength;

    @Min(1)
    private final int maxSummaryLength;

    @Min(1)
    private final int maxDescriptionLength;

    @Min(1)
    private final int maxPayloadBytes;

    @NotNull private final Duration maxFutureSkew;
    @NotNull private final Duration maxPastAge;

    @NotEmpty private final List<Integer> supportedSchemaVersions;

    public Ingestion(
        int maxLabels,
        int maxAnnotations,
        int maxLabelKeyLength,
        int maxLabelValueLength,
        int maxAlertNameLength,
        int maxSummaryLength,
        int maxDescriptionLength,
        int maxPayloadBytes,
        Duration maxFutureSkew,
        Duration maxPastAge,
        List<Integer> supportedSchemaVersions) {
      this.maxLabels = maxLabels;
      this.maxAnnotations = maxAnnotations;
      this.maxLabelKeyLength = maxLabelKeyLength;
      this.maxLabelValueLength = maxLabelValueLength;
      this.maxAlertNameLength = maxAlertNameLength;
      this.maxSummaryLength = maxSummaryLength;
      this.maxDescriptionLength = maxDescriptionLength;
      this.maxPayloadBytes = maxPayloadBytes;
      this.maxFutureSkew = maxFutureSkew;
      this.maxPastAge = maxPastAge;
      this.supportedSchemaVersions = supportedSchemaVersions;
    }

    public int maxLabels() {
      return maxLabels;
    }

    public int maxAnnotations() {
      return maxAnnotations;
    }

    public int maxLabelKeyLength() {
      return maxLabelKeyLength;
    }

    public int maxLabelValueLength() {
      return maxLabelValueLength;
    }

    public int maxAlertNameLength() {
      return maxAlertNameLength;
    }

    public int maxSummaryLength() {
      return maxSummaryLength;
    }

    public int maxDescriptionLength() {
      return maxDescriptionLength;
    }

    public int maxPayloadBytes() {
      return maxPayloadBytes;
    }

    public Duration maxFutureSkew() {
      return maxFutureSkew;
    }

    public Duration maxPastAge() {
      return maxPastAge;
    }

    public List<Integer> supportedSchemaVersions() {
      return supportedSchemaVersions;
    }
  }

  /**
   * Generic-webhook HMAC signing (section 4). Secrets are loaded from environment only — see
   * .env.example.
   */
  public static class Hmac {
    @NotBlank private final String currentSecretId;
    @NotBlank private final String currentSecret;
    private final String previousSecretId;
    private final String previousSecret;
    @NotNull private final Duration replayWindow;

    public Hmac(
        String currentSecretId,
        String currentSecret,
        String previousSecretId,
        String previousSecret,
        Duration replayWindow) {
      this.currentSecretId = currentSecretId;
      this.currentSecret = currentSecret;
      this.previousSecretId = previousSecretId;
      this.previousSecret = previousSecret;
      this.replayWindow = replayWindow;
    }

    public String currentSecretId() {
      return currentSecretId;
    }

    public String currentSecret() {
      return currentSecret;
    }

    public String previousSecretId() {
      return previousSecretId;
    }

    public String previousSecret() {
      return previousSecret;
    }

    public Duration replayWindow() {
      return replayWindow;
    }
  }

  /**
   * Alertmanager receiver authentication (section 3) — a static shared bearer token, never logged.
   */
  public static class Alertmanager {
    @NotBlank private final String sharedToken;

    public Alertmanager(String sharedToken) {
      this.sharedToken = sharedToken;
    }

    public String sharedToken() {
      return sharedToken;
    }
  }

  /** Per-connector rate limiting (section 4). */
  public static class RateLimit {
    @Min(1)
    private final int maxRequestsPerWindow;

    @NotNull private final Duration window;

    public RateLimit(int maxRequestsPerWindow, Duration window) {
      this.maxRequestsPerWindow = maxRequestsPerWindow;
      this.window = window;
    }

    public int maxRequestsPerWindow() {
      return maxRequestsPerWindow;
    }

    public Duration window() {
      return window;
    }
  }

  /** Deterministic correlation-rule settings (section 8). */
  public static class Correlation {
    @NotNull private final Duration window;

    @Min(1)
    private final int maxCandidates;

    public Correlation(Duration window, int maxCandidates) {
      this.window = window;
      this.maxCandidates = maxCandidates;
    }

    /** How far back an open incident is still considered a correlation candidate. */
    public Duration window() {
      return window;
    }

    /**
     * Bounds the candidate query so correlation never scans an unbounded number of open incidents.
     */
    public int maxCandidates() {
      return maxCandidates;
    }
  }

  /** Notification dispatch (section 11). */
  public static class Notification {
    @NotBlank private final String incidentLinkBaseUrl;
    private final String webhookSinkUrl;
    private final String emailFrom;
    private final String emailTo;

    @Min(1)
    private final int maxAttempts;

    @NotNull private final Duration initialBackoff;
    @NotNull private final Duration maxBackoff;
    private final double backoffJitter;

    @NotNull private final Duration pollingInterval;

    @Min(1)
    private final int batchSize;

    public Notification(
        String incidentLinkBaseUrl,
        String webhookSinkUrl,
        String emailFrom,
        String emailTo,
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff,
        double backoffJitter,
        Duration pollingInterval,
        int batchSize) {
      this.incidentLinkBaseUrl = incidentLinkBaseUrl;
      this.webhookSinkUrl = webhookSinkUrl;
      this.emailFrom = emailFrom;
      this.emailTo = emailTo;
      this.maxAttempts = maxAttempts;
      this.initialBackoff = initialBackoff;
      this.maxBackoff = maxBackoff;
      this.backoffJitter = backoffJitter;
      this.pollingInterval = pollingInterval;
      this.batchSize = batchSize;
    }

    /** Blank disables the email channel. */
    public String emailFrom() {
      return emailFrom;
    }

    /** Blank disables the email channel. */
    public String emailTo() {
      return emailTo;
    }

    /**
     * Base URL used to build a human-clickable incident link in a notification — never a
     * payload-controlled value.
     */
    public String incidentLinkBaseUrl() {
      return incidentLinkBaseUrl;
    }

    /**
     * Local demonstration webhook sink (see docker-compose's "webhook-sink" service). Blank
     * disables the channel.
     */
    public String webhookSinkUrl() {
      return webhookSinkUrl;
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

    public double backoffJitter() {
      return backoffJitter;
    }

    public Duration pollingInterval() {
      return pollingInterval;
    }

    public int batchSize() {
      return batchSize;
    }
  }

  /** Escalation scheduling (section 12). */
  public static class Escalation {
    @NotNull private final Duration pollingInterval;

    @Min(1)
    private final int batchSize;

    public Escalation(Duration pollingInterval, int batchSize) {
      this.pollingInterval = pollingInterval;
      this.batchSize = batchSize;
    }

    public Duration pollingInterval() {
      return pollingInterval;
    }

    public int batchSize() {
      return batchSize;
    }
  }

  /** Routing-rule configuration (section 10), validated at startup — see {@code RoutingEngine}. */
  public static class Routing {
    @NotEmpty private final List<RoutingRuleConfig> rules;
    @NotNull private final RouteConfig defaultRoute;
    @NotNull private final BusinessHours businessHours;

    public Routing(
        List<RoutingRuleConfig> rules, RouteConfig defaultRoute, BusinessHours businessHours) {
      this.rules = rules;
      this.defaultRoute = defaultRoute;
      this.businessHours = businessHours;
    }

    public List<RoutingRuleConfig> rules() {
      return rules;
    }

    public RouteConfig defaultRoute() {
      return defaultRoute;
    }

    public BusinessHours businessHours() {
      return businessHours;
    }
  }

  /**
   * One versioned routing rule: matches on the listed (nullable = wildcard) fields, in list order —
   * first match wins. See {@code RoutingEngine}.
   */
  public record RoutingRuleConfig(
      @NotBlank String id,
      @Min(1) int version,
      String severity,
      String service,
      String environment,
      String source,
      String teamLabel,
      /**
       * {@code null} = wildcard; {@code true}/{@code false} require the alert to arrive
       * inside/outside business hours (see {@link BusinessHours}).
       */
      Boolean businessHoursOnly,
      RouteConfig route) {}

  /** Fixed local business-hours window used by the {@code businessHoursOnly} routing-rule field. */
  public static class BusinessHours {
    @NotBlank private final String zoneId;

    @Min(0)
    private final int startHour;

    @Min(0)
    private final int endHour;

    public BusinessHours(String zoneId, int startHour, int endHour) {
      this.zoneId = zoneId;
      this.startHour = startHour;
      this.endHour = endHour;
    }

    public String zoneId() {
      return zoneId;
    }

    /** Inclusive local start hour (0-23), Monday-Friday. */
    public int startHour() {
      return startHour;
    }

    /** Exclusive local end hour (0-23), Monday-Friday. */
    public int endHour() {
      return endHour;
    }
  }

  /** What a matched routing rule (or the default route) decides (section 10). */
  public record RouteConfig(
      @NotBlank String team,
      @NotEmpty List<String> channels,
      @NotNull Duration escalationDelay,
      boolean queueAiTriage,
      boolean suppress) {}
}
