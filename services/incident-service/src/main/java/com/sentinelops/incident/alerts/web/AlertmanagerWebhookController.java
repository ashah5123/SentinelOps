package com.sentinelops.incident.alerts.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.canonical.AlertValidationException;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import com.sentinelops.incident.alerts.canonical.PayloadHasher;
import com.sentinelops.incident.alerts.connector.alertmanager.AlertmanagerAlert;
import com.sentinelops.incident.alerts.connector.alertmanager.AlertmanagerAlertMapper;
import com.sentinelops.incident.alerts.connector.alertmanager.AlertmanagerWebhookPayload;
import com.sentinelops.incident.alerts.ingestion.AlertIngestionService;
import com.sentinelops.incident.alerts.ingestion.IngestionOutcome;
import com.sentinelops.incident.alerts.observability.AlertMetrics;
import com.sentinelops.incident.web.filter.CorrelationIdFilter;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accepts the actual Prometheus Alertmanager {@code webhook_config} payload format (section 3).
 * Never covered by the JWT filter chain — see {@code AlertWebhookSecurityConfig} — this endpoint
 * authenticates with a single static shared bearer token instead, since Alertmanager cannot perform
 * an OAuth2 client-credentials exchange (the same reasoning as the existing Prometheus
 * scrape-endpoint filter chain, see {@code SecurityConfig.metricsFilterChain}).
 *
 * <p>Each alert in the batch is mapped and ingested independently: one malformed alert is recorded
 * as an error for that alert only and never discards the rest of a valid batch.
 */
@RestController
@RequestMapping("/api/v1/alerts/webhooks/alertmanager")
public class AlertmanagerWebhookController {

  private static final Logger log = LoggerFactory.getLogger(AlertmanagerWebhookController.class);
  private static final String CONNECTOR_TYPE = "ALERTMANAGER";

  private final AlertIngestionService ingestionService;
  private final AlertmanagerAlertMapper mapper;
  private final ObjectMapper objectMapper;
  private final AlertsProperties properties;
  private final ConnectorRateLimiter rateLimiter;
  private final AlertMetrics metrics;

  public AlertmanagerWebhookController(
      AlertIngestionService ingestionService,
      AlertmanagerAlertMapper mapper,
      ObjectMapper objectMapper,
      AlertsProperties properties,
      ConnectorRateLimiter rateLimiter,
      AlertMetrics metrics) {
    this.ingestionService = ingestionService;
    this.mapper = mapper;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.rateLimiter = rateLimiter;
    this.metrics = metrics;
  }

  @Operation(
      summary = "Alertmanager webhook_config receiver",
      description =
          "Authenticated with a static shared bearer token (see sentinelops.alerts.alertmanager.shared-token). Rate-limited per connector.")
  @PostMapping
  public ResponseEntity<?> receive(
      @RequestHeader(name = "Authorization", required = false) String authorizationHeader,
      @RequestBody String rawBody,
      HttpServletRequest servletRequest) {
    Timer.Sample timerSample = metrics.startIngestionTimer();
    try {
      if (!isAuthorized(authorizationHeader)) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
      }
      if (!rateLimiter.tryAcquire(CONNECTOR_TYPE)) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
      }
      if (rawBody.getBytes(StandardCharsets.UTF_8).length
          > properties.ingestion().maxPayloadBytes()) {
        metrics.batchProcessed(CONNECTOR_TYPE, "rejected_too_large");
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).build();
      }

      AlertmanagerWebhookPayload payload;
      try {
        payload = objectMapper.readValue(rawBody, AlertmanagerWebhookPayload.class);
      } catch (Exception e) {
        metrics.batchProcessed(CONNECTOR_TYPE, "rejected_malformed");
        return ResponseEntity.badRequest()
            .body(IngestionBatchResponse.of(0, 0, 0, List.of("malformed JSON body")));
      }

      String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
      String rawPayloadHash = PayloadHasher.sha256Hex(rawBody);

      List<AlertmanagerAlert> alerts = payload.alerts() == null ? List.of() : payload.alerts();
      int accepted = 0;
      int duplicates = 0;
      List<String> errors = new ArrayList<>();

      for (AlertmanagerAlert alert : alerts) {
        try {
          CanonicalAlert canonical = mapper.map(alert, rawPayloadHash);
          IngestionOutcome outcome =
              ingestionService.ingest(canonical, rawPayloadHash, correlationId);
          if (outcome instanceof IngestionOutcome.DuplicateDelivery) {
            duplicates++;
          } else {
            accepted++;
          }
        } catch (AlertValidationException e) {
          errors.add("alert '" + safeAlertName(alert) + "': " + e.violations());
        } catch (RuntimeException e) {
          log.warn(
              "Unexpected error ingesting one Alertmanager alert: {}",
              e.getClass().getSimpleName());
          errors.add("alert '" + safeAlertName(alert) + "': internal error");
        }
      }

      metrics.batchProcessed(CONNECTOR_TYPE, errors.isEmpty() ? "accepted" : "partial");
      return ResponseEntity.status(HttpStatus.ACCEPTED)
          .body(IngestionBatchResponse.of(alerts.size(), accepted, duplicates, errors));
    } finally {
      metrics.stopIngestionTimer(timerSample, CONNECTOR_TYPE);
    }
  }

  private boolean isAuthorized(String authorizationHeader) {
    if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
      return false;
    }
    String provided = authorizationHeader.substring("Bearer ".length()).trim();
    String expected = properties.alertmanager().sharedToken();
    return MessageDigest.isEqual(
        provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
  }

  private String safeAlertName(AlertmanagerAlert alert) {
    if (alert.labels() == null) {
      return "unknown";
    }
    String name = alert.labels().get("alertname");
    return name == null ? "unknown" : name;
  }
}
