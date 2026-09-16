package com.sentinelops.incident.alerts.ingestion;

import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import com.sentinelops.incident.alerts.canonical.CanonicalAlertValidator;
import com.sentinelops.incident.alerts.fingerprint.AlertFingerprinter;
import com.sentinelops.incident.alerts.observability.AlertMetrics;
import com.sentinelops.incident.application.OutboxWriter;
import com.sentinelops.incident.events.AlertIngestedPayload;
import com.sentinelops.incident.events.EventTypes;
import com.sentinelops.incident.observability.Spans;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable alert ingestion (section 5). Everything in {@link #ingest} runs in one database
 * transaction: validate, compute the fingerprint, record the alert event (delivery-idempotent via
 * {@code dedup_key}), and append the {@code alert.ingested.v1} outbox event — commit or nothing. A
 * caller only ever sees a success response after this transaction has committed, so "accepted"
 * always means durably accepted, never merely held in memory.
 *
 * <p>Everything past this point — fingerprint occurrence tracking, incident creation/attachment,
 * correlation, routing, and notification — happens asynchronously in {@code
 * AlertProcessingListener}, consuming the outbox event this method appends. This keeps the
 * connector's HTTP response fast and matches the existing anomaly-ingestion precedent ({@code
 * AnomalyIncidentProcessor}) of doing the heavier domain work in a consumer, not the request
 * thread.
 */
@Service
public class AlertIngestionService {

  private final CanonicalAlertValidator validator;
  private final AlertFingerprinter fingerprinter;
  private final AlertEventRepository alertEventRepository;
  private final OutboxWriter outboxWriter;
  private final AlertMetrics metrics;
  private final Spans spans;

  public AlertIngestionService(
      CanonicalAlertValidator validator,
      AlertFingerprinter fingerprinter,
      AlertEventRepository alertEventRepository,
      OutboxWriter outboxWriter,
      AlertMetrics metrics,
      Spans spans) {
    this.validator = validator;
    this.fingerprinter = fingerprinter;
    this.alertEventRepository = alertEventRepository;
    this.outboxWriter = outboxWriter;
    this.metrics = metrics;
    this.spans = spans;
  }

  @Transactional
  public IngestionOutcome ingest(
      CanonicalAlert alert, String rawPayloadHash, String correlationId) {
    return spans.inSpan(
        "alerts.ingest",
        Map.of("connector_type", alert.connectorType()),
        () -> ingestInSpan(alert, rawPayloadHash, correlationId));
  }

  private IngestionOutcome ingestInSpan(
      CanonicalAlert alert, String rawPayloadHash, String correlationId) {
    validator.validate(alert);

    String fingerprint = fingerprinter.fingerprint(alert);
    String dedupKey = computeDedupKey(alert, fingerprint);
    UUID id = UUID.randomUUID();
    Instant ingestedAt = Instant.now();

    boolean inserted =
        alertEventRepository.tryInsert(
            id,
            alert,
            fingerprint,
            AlertFingerprinter.VERSION,
            ingestedAt,
            rawPayloadHash,
            dedupKey,
            correlationId);

    if (!inserted) {
      AlertEventRow existing =
          alertEventRepository
              .findByDedupKey(dedupKey)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "dedup_key conflict but row not found: " + dedupKey));
      metrics.alertIngested(alert.connectorType(), "duplicate_delivery");
      return new IngestionOutcome.DuplicateDelivery(existing.id(), fingerprint);
    }

    outboxWriter.append(
        "AlertEvent",
        id,
        EventTypes.ALERT_INGESTED_V1,
        EventTypes.ALERT_INGESTED_SCHEMA_VERSION,
        new AlertIngestedPayload(
            id,
            fingerprint,
            AlertFingerprinter.VERSION,
            alert.connectorType(),
            alert.source(),
            alert.status().name()),
        correlationId);

    metrics.alertIngested(alert.connectorType(), "accepted");
    return new IngestionOutcome.Accepted(id, fingerprint);
  }

  /**
   * Delivery-idempotency key: identifies "this exact webhook delivery," not "this alert condition"
   * (that's the fingerprint's job — see semantic dedup in {@code AlertProcessingListener}). Prefers
   * the source's own external identifier (e.g. Alertmanager's fingerprint) when present, since it
   * directly identifies a specific delivery; falls back to our own fingerprint otherwise.
   */
  private String computeDedupKey(CanonicalAlert alert, String fingerprint) {
    String identifier =
        alert.externalId() != null && !alert.externalId().isBlank()
            ? alert.externalId()
            : fingerprint;
    String raw =
        alert.connectorType()
            + "|"
            + alert.source()
            + "|"
            + identifier
            + "|"
            + alert.status()
            + "|"
            + alert.sourceTimestamp();
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must always be available", e);
    }
  }
}
