package com.sentinelops.incident.alerts.connector.webhook;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/**
 * The generic versioned webhook connector's request body — a direct, explicit representation of the
 * canonical schema fields (section 4), for demonstration and future integrations that don't speak
 * Alertmanager's format.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GenericWebhookAlert(
    String source,
    String externalId,
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
    String generatorUrl,
    int schemaVersion) {}
