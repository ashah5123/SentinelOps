package com.sentinelops.incident.alerts.canonical;

import java.time.Instant;
import java.util.Map;

/**
 * SentinelOps' versioned internal alert schema (schema version {@link #schemaVersion()}) — the only
 * shape any downstream code (fingerprinting, deduplication, correlation, routing, notification)
 * ever depends on. Every connector (Alertmanager, the generic webhook) maps its own wire format
 * onto this record and nothing else crosses that boundary.
 *
 * <p>Deliberately excludes anything connector-specific (auth headers, transport metadata) and
 * anything unbounded (the full raw payload) — see {@link #rawPayloadHash()}, which is a SHA-256
 * digest, never the payload itself, matching the "no unrestricted raw payload" requirement.
 *
 * <p>{@link #sourceTimestamp()} is when the alert condition itself occurred/was evaluated by the
 * source system (event time); ingestion time is intentionally not a field here — see {@code
 * IngestedAlert}, which wraps this with the ingestion-computed fields (ingested-at timestamp,
 * fingerprint) that only exist once SentinelOps has processed the alert.
 */
public record CanonicalAlert(
    String connectorType,
    String source,
    String externalId,
    AlertStatus status,
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
    String generatorUrl,
    int schemaVersion,
    String rawPayloadHash) {

  public static final int CURRENT_SCHEMA_VERSION = 1;
}
