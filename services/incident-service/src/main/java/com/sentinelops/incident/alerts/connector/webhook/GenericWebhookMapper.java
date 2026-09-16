package com.sentinelops.incident.alerts.connector.webhook;

import com.sentinelops.incident.alerts.canonical.AlertStatus;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GenericWebhookMapper {

  public CanonicalAlert map(GenericWebhookAlert alert, String rawPayloadHash) {
    AlertStatus status =
        "resolved".equalsIgnoreCase(alert.status()) ? AlertStatus.RESOLVED : AlertStatus.FIRING;
    return new CanonicalAlert(
        "GENERIC_WEBHOOK",
        alert.source(),
        alert.externalId(),
        status,
        alert.alertName(),
        alert.summary(),
        alert.description(),
        alert.severity(),
        alert.service(),
        alert.environment(),
        alert.region(),
        alert.labels() == null ? Map.of() : alert.labels(),
        alert.annotations() == null ? Map.of() : alert.annotations(),
        alert.sourceTimestamp(),
        alert.generatorUrl(),
        alert.schemaVersion(),
        rawPayloadHash);
  }
}
