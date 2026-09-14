package com.sentinelops.telemetry.adapters.prometheus;

import com.sentinelops.telemetry.domain.Evidence;
import com.sentinelops.telemetry.domain.EvidenceType;
import com.sentinelops.telemetry.domain.SourceSystem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Converts a raw Prometheus {@code query_range} response into evidence-ready builders.
 *
 * <p>Every sample is validated before being kept: non-finite values ({@code NaN}, {@code +Inf},
 * {@code -Inf}) are rejected (Prometheus represents these as those literal strings in its JSON
 * response), and only a fixed allow-list of label keys is preserved as {@link
 * Evidence#getAttributes()} — an arbitrary label set from a misconfigured or malicious target could
 * otherwise grow without bound and blow up storage/index cardinality.
 */
@Component
public class PrometheusNormalizer {

  private static final Logger log = LoggerFactory.getLogger(PrometheusNormalizer.class);

  /** Only these label keys are ever kept — everything else is dropped, not merely truncated. */
  private static final Set<String> ALLOWED_LABEL_KEYS =
      Set.of("job", "instance", "method", "status", "status_code", "outcome", "uri", "exception");

  private static final int MAX_ATTRIBUTES = ALLOWED_LABEL_KEYS.size();

  public List<Evidence.Builder> normalize(
      String promQuery, String monitoredService, PrometheusResponse response) {
    List<Evidence.Builder> results = new ArrayList<>();
    if (response == null || response.data() == null || response.data().result() == null) {
      return results;
    }
    for (PrometheusResponse.Result result : response.data().result()) {
      if (result.values() == null) {
        continue;
      }
      for (List<Object> point : result.values()) {
        toEvidence(promQuery, monitoredService, result.metric(), point).ifPresent(results::add);
      }
    }
    return results;
  }

  private java.util.Optional<Evidence.Builder> toEvidence(
      String promQuery, String monitoredService, Map<String, String> metric, List<Object> point) {
    if (point == null || point.size() != 2) {
      return java.util.Optional.empty();
    }
    double epochSeconds;
    double value;
    try {
      epochSeconds = Double.parseDouble(String.valueOf(point.get(0)));
      value = Double.parseDouble(String.valueOf(point.get(1)));
    } catch (NumberFormatException e) {
      log.debug("Rejecting Prometheus sample with malformed timestamp/value: {}", point);
      return java.util.Optional.empty();
    }
    if (!Double.isFinite(value)) {
      log.debug("Rejecting non-finite Prometheus sample value for query [{}]", promQuery);
      return java.util.Optional.empty();
    }

    Instant observedAt = Instant.ofEpochMilli(Math.round(epochSeconds * 1000));
    String metricName = metric != null ? metric.getOrDefault("__name__", promQuery) : promQuery;
    Map<String, String> attributes = boundedAttributes(metric);

    String reference =
        "prometheus:query_range?query=" + promQuery + "&monitoredService=" + monitoredService;

    Evidence.Builder builder =
        Evidence.builder(EvidenceType.METRIC, SourceSystem.PROMETHEUS, monitoredService)
            .observedAt(observedAt)
            .metricName(metricName)
            .metricValue(value)
            .summary(summarize(metricName, value))
            .sourceReference(reference)
            .attributes(attributes);
    return java.util.Optional.of(builder);
  }

  private Map<String, String> boundedAttributes(Map<String, String> metric) {
    if (metric == null) {
      return Map.of();
    }
    Map<String, String> bounded = new LinkedHashMap<>();
    for (String key : ALLOWED_LABEL_KEYS) {
      String value = metric.get(key);
      if (value != null && bounded.size() < MAX_ATTRIBUTES) {
        bounded.put(key, truncate(value, 200));
      }
    }
    return bounded;
  }

  private String summarize(String metricName, double value) {
    return truncate("%s=%s".formatted(metricName, value), 500);
  }

  private String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
