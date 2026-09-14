package com.sentinelops.telemetry.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Computes a deterministic fingerprint for a normalized evidence record, so re-polling an
 * overlapping time window (see {@code IngestionCheckpoint}) or redelivering the same Kafka event
 * never creates a duplicate {@code Evidence} row — the database's unique constraint on {@code
 * fingerprint} is the authoritative guard; this service exists to compute the same fingerprint for
 * the same underlying fact every time.
 */
@Component
public class FingerprintService {

  public String metricFingerprint(
      String sourceService, String metricName, Instant observedAt, Map<String, String> labels) {
    return sha256(
        "METRIC|"
            + sourceService
            + "|"
            + metricName
            + "|"
            + observedAt.getEpochSecond()
            + "|"
            + canonicalize(labels));
  }

  public String logFingerprint(String sourceService, Instant observedAt, String summary) {
    return sha256("LOG|" + sourceService + "|" + observedAt.toEpochMilli() + "|" + summary);
  }

  public String traceFingerprint(String traceId, String spanId) {
    return sha256("TRACE|" + traceId + "|" + (spanId == null ? "" : spanId));
  }

  public String deploymentFingerprint(String sourceEventId) {
    return sha256("DEPLOYMENT|" + sourceEventId);
  }

  public String dependencyFingerprint(String sourceEventId) {
    return sha256("DEPENDENCY|" + sourceEventId);
  }

  private String canonicalize(Map<String, String> labels) {
    if (labels == null || labels.isEmpty()) {
      return "";
    }
    return new TreeMap<>(labels).toString();
  }

  private String sha256(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
