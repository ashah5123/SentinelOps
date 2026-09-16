package com.sentinelops.incident.alerts.correlation;

import java.time.Instant;
import java.util.UUID;

/** One currently-open incident a new alert occurrence could potentially be correlated with. */
public record CorrelationCandidate(
    UUID incidentId,
    String service,
    String environment,
    String region,
    Instant detectedAt,
    String source) {}
