package com.sentinelops.incident.alerts.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelops.incident.alerts.AlertsProperties;
import com.sentinelops.incident.alerts.canonical.AlertValidationException;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import com.sentinelops.incident.alerts.canonical.PayloadHasher;
import com.sentinelops.incident.alerts.connector.webhook.GenericWebhookAlert;
import com.sentinelops.incident.alerts.connector.webhook.GenericWebhookMapper;
import com.sentinelops.incident.alerts.connector.webhook.HmacSignatureVerifier;
import com.sentinelops.incident.alerts.ingestion.AlertIngestionService;
import com.sentinelops.incident.alerts.ingestion.IngestionOutcome;
import com.sentinelops.incident.alerts.observability.AlertMetrics;
import com.sentinelops.incident.web.filter.CorrelationIdFilter;
import io.micrometer.core.instrument.Timer;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
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
 * The generic, versioned webhook connector (section 4) — for demonstration and future integrations
 * that don't speak Alertmanager's format. Secured with HMAC-SHA256 request signing (see {@link
 * HmacSignatureVerifier}), never JWT (this is a machine-to-machine connector, not an interactive
 * user) and never a weakening of the interactive JWT chain — see {@code
 * AlertWebhookSecurityConfig}, which scopes this path to its own filter chain entirely.
 */
@RestController
@RequestMapping("/api/v1/alerts/webhooks/generic/v1")
public class GenericWebhookController {

  private static final Logger log = LoggerFactory.getLogger(GenericWebhookController.class);
  private static final String CONNECTOR_TYPE = "GENERIC_WEBHOOK";

  private final AlertIngestionService ingestionService;
  private final GenericWebhookMapper mapper;
  private final HmacSignatureVerifier signatureVerifier;
  private final ObjectMapper objectMapper;
  private final AlertsProperties properties;
  private final ConnectorRateLimiter rateLimiter;
  private final AlertMetrics metrics;

  public GenericWebhookController(
      AlertIngestionService ingestionService,
      GenericWebhookMapper mapper,
      HmacSignatureVerifier signatureVerifier,
      ObjectMapper objectMapper,
      AlertsProperties properties,
      ConnectorRateLimiter rateLimiter,
      AlertMetrics metrics) {
    this.ingestionService = ingestionService;
    this.mapper = mapper;
    this.signatureVerifier = signatureVerifier;
    this.objectMapper = objectMapper;
    this.properties = properties;
    this.rateLimiter = rateLimiter;
    this.metrics = metrics;
  }

  @Operation(
      summary = "Generic versioned webhook alert connector",
      description =
          "HMAC-SHA256 signed (see docs/development/alert-ingestion.md's signing section). Rate-limited per connector.")
  @PostMapping
  public ResponseEntity<?> receive(
      @RequestHeader(name = "X-SentinelOps-Timestamp", required = false) String timestampHeader,
      @RequestHeader(name = "X-SentinelOps-Signature", required = false) String signatureHeader,
      @RequestHeader(name = "X-SentinelOps-Key-Id", required = false) String keyIdHeader,
      @RequestBody String rawBody,
      HttpServletRequest servletRequest) {
    Timer.Sample timerSample = metrics.startIngestionTimer();
    try {
      HmacSignatureVerifier.Result verification =
          signatureVerifier.verify(rawBody, timestampHeader, signatureHeader, keyIdHeader);
      if (verification instanceof HmacSignatureVerifier.Invalid invalid) {
        boolean isReplay = invalid.reason().contains("replay window");
        if (isReplay) {
          metrics.replayRejected(CONNECTOR_TYPE);
        } else {
          metrics.invalidSignature(CONNECTOR_TYPE);
        }
        // Never echo the reason verbatim to the caller (avoids helping an attacker iterate) —
        // log it (still no secret/signature value) and return a generic 401.
        log.debug("Rejected generic webhook request: {}", invalid.reason());
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

      GenericWebhookAlert alert;
      try {
        alert = objectMapper.readValue(rawBody, GenericWebhookAlert.class);
      } catch (Exception e) {
        metrics.batchProcessed(CONNECTOR_TYPE, "rejected_malformed");
        return ResponseEntity.badRequest()
            .body(IngestionBatchResponse.of(0, 0, 0, List.of("malformed JSON body")));
      }

      String correlationId = CorrelationIdFilter.currentOrGenerate(servletRequest);
      String rawPayloadHash = PayloadHasher.sha256Hex(rawBody);

      try {
        CanonicalAlert canonical = mapper.map(alert, rawPayloadHash);
        IngestionOutcome outcome =
            ingestionService.ingest(canonical, rawPayloadHash, correlationId);
        boolean duplicate = outcome instanceof IngestionOutcome.DuplicateDelivery;
        metrics.batchProcessed(CONNECTOR_TYPE, "accepted");
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(IngestionBatchResponse.of(1, duplicate ? 0 : 1, duplicate ? 1 : 0, List.of()));
      } catch (AlertValidationException e) {
        metrics.batchProcessed(CONNECTOR_TYPE, "rejected_invalid");
        return ResponseEntity.badRequest().body(IngestionBatchResponse.of(1, 0, 0, e.violations()));
      }
    } finally {
      metrics.stopIngestionTimer(timerSample, CONNECTOR_TYPE);
    }
  }
}
