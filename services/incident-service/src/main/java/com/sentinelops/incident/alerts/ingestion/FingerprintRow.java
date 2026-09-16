package com.sentinelops.incident.alerts.ingestion;

import java.time.Instant;
import java.util.UUID;

public record FingerprintRow(
    String fingerprint,
    int fingerprintVersion,
    String source,
    Instant firstSeenAt,
    Instant lastSeenAt,
    int occurrenceCount,
    UUID activeIncidentId,
    String status,
    Instant updatedAt) {}
