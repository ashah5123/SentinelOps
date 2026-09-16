package com.sentinelops.incident.alerts.connector.alertmanager;

import com.sentinelops.incident.alerts.canonical.AlertSeverityMapper;
import com.sentinelops.incident.alerts.canonical.AlertStatus;
import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps one {@link AlertmanagerAlert} onto the canonical schema (section 3). Every alert in a batch
 * is mapped independently — a malformed field in one alert throws only for that alert, never the
 * whole batch (see {@code AlertmanagerWebhookController}).
 */
@Component
public class AlertmanagerAlertMapper {

  /** Alertmanager's sentinel "unset" value for endsAt on a still-firing alert. */
  private static final Instant UNSET_ENDS_AT = Instant.parse("0001-01-01T00:00:00Z");

  public CanonicalAlert map(AlertmanagerAlert alert, String rawPayloadHash) {
    Map<String, String> labels = alert.labels() == null ? Map.of() : alert.labels();
    Map<String, String> annotations = alert.annotations() == null ? Map.of() : alert.annotations();

    AlertStatus status =
        "resolved".equalsIgnoreCase(alert.status()) ? AlertStatus.RESOLVED : AlertStatus.FIRING;
    Instant sourceTimestamp =
        status == AlertStatus.RESOLVED && isSet(alert.endsAt()) ? alert.endsAt() : alert.startsAt();

    return new CanonicalAlert(
        "ALERTMANAGER",
        "alertmanager",
        alert.fingerprint(),
        status,
        labels.get("alertname"),
        annotations.get("summary"),
        annotations.get("description"),
        AlertSeverityMapper.map(labels.get("severity")),
        firstNonBlank(labels.get("service"), labels.get("job")),
        firstNonBlank(labels.get("environment"), labels.get("env")),
        labels.get("region"),
        labels,
        annotations,
        sourceTimestamp,
        alert.generatorURL(),
        CanonicalAlert.CURRENT_SCHEMA_VERSION,
        rawPayloadHash);
  }

  private boolean isSet(Instant endsAt) {
    return endsAt != null && endsAt.isAfter(UNSET_ENDS_AT);
  }

  private String firstNonBlank(String a, String b) {
    if (a != null && !a.isBlank()) {
      return a;
    }
    return b;
  }
}
