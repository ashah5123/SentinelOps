package com.sentinelops.incident.alerts.ingestion;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A persisted row from {@code alerts.alert_events} — the durable record of one ingestion attempt.
 */
public record AlertEventRow(
    UUID id,
    String connectorType,
    String source,
    String externalId,
    String fingerprint,
    int fingerprintVersion,
    String status,
    String alertName,
    String summary,
    String description,
    String severity,
    String service,
    String environment,
    String region,
    Map<String, String> labels,
    Map<String, String> annotations,
    Instant sourceTimestamp,
    Instant ingestedAt,
    String generatorUrl,
    int schemaVersion,
    String rawPayloadHash,
    String dedupKey,
    String correlationId,
    UUID incidentId,
    Instant createdAt) {}
