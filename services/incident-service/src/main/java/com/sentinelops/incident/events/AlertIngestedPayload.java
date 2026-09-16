package com.sentinelops.incident.events;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * Payload of {@code alert.ingested.v1} — published once an alert has been durably accepted (see
 * {@code AlertIngestionService}). Deliberately minimal: the consumer ({@code
 * AlertProcessingListener}) re-reads the full row from {@code alerts.alert_events} by {@code
 * alertEventId} rather than trusting a copy of the data in the event itself, so the two can never
 * drift apart.
 */
public record AlertIngestedPayload(
    @JsonProperty("alertEventId") UUID alertEventId,
    @JsonProperty("fingerprint") String fingerprint,
    @JsonProperty("fingerprintVersion") int fingerprintVersion,
    @JsonProperty("connectorType") String connectorType,
    @JsonProperty("source") String source,
    @JsonProperty("status") String status) {}
