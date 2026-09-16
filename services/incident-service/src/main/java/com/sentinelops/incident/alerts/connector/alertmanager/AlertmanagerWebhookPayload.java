package com.sentinelops.incident.alerts.connector.alertmanager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Map;

/**
 * The actual Prometheus Alertmanager webhook_config payload shape (version "4") — see
 * https://prometheus.io/docs/alerting/latest/configuration/#webhook_config and
 * docs/development/alert-ingestion.md's Alertmanager section.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertmanagerWebhookPayload(
    String version,
    String groupKey,
    Integer truncatedAlerts,
    String status,
    String receiver,
    Map<String, String> groupLabels,
    Map<String, String> commonLabels,
    Map<String, String> commonAnnotations,
    String externalURL,
    List<AlertmanagerAlert> alerts) {}
