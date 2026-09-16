package com.sentinelops.incident.alerts.canonical;

import java.time.Instant;
import java.util.UUID;

/**
 * A {@link CanonicalAlert} enriched with the fields only ingestion itself can compute: a durable
 * identity ({@code id}, matching the persisted {@code alerts.alert_events.id}), the ingestion
 * timestamp (distinct from the alert's own {@link CanonicalAlert#sourceTimestamp()} — event time
 * vs. ingestion time), and the deterministic fingerprint (see {@code AlertFingerprinter}).
 */
public record IngestedAlert(
    UUID id,
    CanonicalAlert alert,
    Instant ingestedAt,
    String fingerprint,
    int fingerprintVersion) {}
