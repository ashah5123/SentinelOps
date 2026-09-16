package com.sentinelops.incident.alerts.connector.alertmanager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.Map;

/** One alert within an Alertmanager webhook payload (the {@code v4} webhook format). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerAlert(
    String status,
    Map<String, String> labels,
    Map<String, String> annotations,
    Instant startsAt,
    Instant endsAt,
    String generatorURL,
    String fingerprint) {}
