package com.sentinelops.incident.alerts.web;

import com.sentinelops.incident.alerts.ingestion.AlertEventRow;
import java.time.Instant;
import java.util.UUID;

/**
 * Operator-safe view of an {@code alerts.alert_events} row — never the raw payload, only its hash.
 */
public record AlertEventResponse(
    UUID id,
    String connectorType,
    String source,
    String fingerprint,
    int fingerprintVersion,
    String status,
    String alertName,
    String summary,
    String severity,
    String service,
    String environment,
    String region,
    Instant sourceTimestamp,
    Instant ingestedAt,
    String rawPayloadHash) {

  public static AlertEventResponse from(AlertEventRow row) {
    return new AlertEventResponse(
        row.id(),
        row.connectorType(),
        row.source(),
        row.fingerprint(),
        row.fingerprintVersion(),
        row.status(),
        row.alertName(),
        row.summary(),
        row.severity(),
        row.service(),
        row.environment(),
        row.region(),
        row.sourceTimestamp(),
        row.ingestedAt(),
        row.rawPayloadHash());
  }
}
