package com.sentinelops.incident.alerts.correlation;

import java.util.UUID;

/**
 * The most recent alert's service/environment/region/source for a given incident — used to build
 * {@link CorrelationCandidate}s.
 */
public record IncidentAlertContext(
    UUID incidentId, String service, String environment, String region, String source) {}
