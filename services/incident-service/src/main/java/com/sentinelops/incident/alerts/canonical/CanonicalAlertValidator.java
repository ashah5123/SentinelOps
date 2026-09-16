package com.sentinelops.incident.alerts.canonical;

import com.sentinelops.incident.alerts.AlertsProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates a {@link CanonicalAlert} against section 2's rules before it is ever persisted.
 * Collects every violation rather than failing on the first, so a caller gets one clear, bounded
 * error response instead of having to fix and resubmit one field at a time.
 */
@Component
public class CanonicalAlertValidator {

  private static final Set<String> ALLOWED_URL_SCHEMES = Set.of("http", "https");
  private static final Set<String> VALID_SEVERITIES = Set.of("SEV1", "SEV2", "SEV3", "SEV4");

  private final AlertsProperties.Ingestion limits;

  public CanonicalAlertValidator(AlertsProperties properties) {
    this.limits = properties.ingestion();
  }

  public void validate(CanonicalAlert alert) {
    List<String> violations = new ArrayList<>();

    requireNonBlank(alert.source(), "source", violations);
    requireNonBlank(alert.alertName(), "alertName", violations);
    if (alert.status() == null) {
      violations.add("status is required (FIRING or RESOLVED)");
    }
    if (alert.sourceTimestamp() == null) {
      violations.add("sourceTimestamp is required");
    } else {
      validateTimestampRange(alert.sourceTimestamp(), violations);
    }

    maxLength(alert.source(), 100, "source", violations);
    maxLength(alert.externalId(), 200, "externalId", violations);
    maxLength(alert.alertName(), limits.maxAlertNameLength(), "alertName", violations);
    maxLength(alert.summary(), limits.maxSummaryLength(), "summary", violations);
    maxLength(alert.description(), limits.maxDescriptionLength(), "description", violations);
    maxLength(alert.service(), 100, "service", violations);
    maxLength(alert.environment(), 50, "environment", violations);
    maxLength(alert.region(), 50, "region", violations);

    if (alert.severity() != null && !VALID_SEVERITIES.contains(alert.severity())) {
      violations.add("severity must be one of " + VALID_SEVERITIES + " when present");
    }

    if (!limits.supportedSchemaVersions().contains(alert.schemaVersion())) {
      violations.add(
          "schemaVersion "
              + alert.schemaVersion()
              + " is not supported (supported: "
              + limits.supportedSchemaVersions()
              + ")");
    }

    validateLabelMap(alert.labels(), "labels", limits.maxLabels(), violations);
    validateLabelMap(alert.annotations(), "annotations", limits.maxAnnotations(), violations);

    validateGeneratorUrl(alert.generatorUrl(), violations);

    if (!violations.isEmpty()) {
      throw new AlertValidationException(violations);
    }
  }

  private void validateTimestampRange(Instant sourceTimestamp, List<String> violations) {
    Instant now = Instant.now();
    if (sourceTimestamp.isAfter(now.plus(limits.maxFutureSkew()))) {
      violations.add("sourceTimestamp is too far in the future");
    }
    if (sourceTimestamp.isBefore(now.minus(limits.maxPastAge()))) {
      violations.add("sourceTimestamp is too far in the past");
    }
  }

  private void validateLabelMap(
      Map<String, String> map, String fieldName, int maxEntries, List<String> violations) {
    if (map == null) {
      return;
    }
    if (map.size() > maxEntries) {
      violations.add(
          fieldName + " has " + map.size() + " entries, exceeding the limit of " + maxEntries);
    }
    for (Map.Entry<String, String> entry : map.entrySet()) {
      if (entry.getKey() == null || entry.getKey().isBlank()) {
        violations.add(fieldName + " contains a blank key");
        continue;
      }
      if (entry.getKey().length() > limits.maxLabelKeyLength()) {
        violations.add(fieldName + " key '" + entry.getKey() + "' exceeds the maximum key length");
      }
      String value = entry.getValue();
      if (value != null && value.length() > limits.maxLabelValueLength()) {
        violations.add(
            fieldName + " value for key '" + entry.getKey() + "' exceeds the maximum value length");
      }
    }
  }

  private void validateGeneratorUrl(String generatorUrl, List<String> violations) {
    if (generatorUrl == null || generatorUrl.isBlank()) {
      return;
    }
    if (generatorUrl.length() > 500) {
      violations.add("generatorUrl exceeds the maximum length");
      return;
    }
    try {
      URI uri = new URI(generatorUrl);
      String scheme = uri.getScheme();
      if (scheme == null
          || !ALLOWED_URL_SCHEMES.contains(scheme.toLowerCase(java.util.Locale.ROOT))) {
        violations.add("generatorUrl must use an http or https scheme");
      }
    } catch (URISyntaxException e) {
      violations.add("generatorUrl is not a valid URL");
    }
  }

  private void requireNonBlank(String value, String fieldName, List<String> violations) {
    if (value == null || value.isBlank()) {
      violations.add(fieldName + " is required");
    }
  }

  private void maxLength(String value, int max, String fieldName, List<String> violations) {
    if (value != null && value.length() > max) {
      violations.add(fieldName + " exceeds the maximum length of " + max);
    }
  }
}
