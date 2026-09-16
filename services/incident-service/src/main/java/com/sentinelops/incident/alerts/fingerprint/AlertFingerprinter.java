package com.sentinelops.incident.alerts.fingerprint;

import com.sentinelops.incident.alerts.canonical.CanonicalAlert;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Deterministic, versioned alert fingerprinting (section 6).
 *
 * <p><b>Version 1</b> hashes exactly: {@code source}, {@code alertName}, {@code service}, {@code
 * environment}, and the subset of {@code labels} whose key is in {@link #IDENTITY_LABEL_KEYS} — a
 * fixed allow-list of labels that identify *what* is alerting (instance, pod, container, job,
 * namespace, device, mountpoint) rather than *when* or *why*. Deliberately excludes: {@code
 * externalId} (a source-assigned identifier whose stability depends on that source's own
 * grouping/routing configuration — not trusted as an identity input by this version; a future v2
 * could opt specific sources in), {@code region} (topology metadata, not identity), every
 * annotation (free text, meant to be volatile/descriptive), and anything not explicitly listed
 * (timestamps, descriptions, request/trace IDs, randomly generated identifiers).
 *
 * <p>All inputs are normalized (trimmed, lower-cased) and labels are sorted by key before hashing,
 * so label order and casing never change the result — see {@code AlertFingerprinterTest}.
 *
 * <p>Bumping {@link #VERSION} is how a future algorithm change is introduced without silently
 * reinterpreting old fingerprints: {@code alerts.alert_fingerprints.fingerprint_version} records
 * which version produced each stored fingerprint.
 */
@Component
public class AlertFingerprinter {

  public static final int VERSION = 1;

  private static final Set<String> IDENTITY_LABEL_KEYS =
      Set.of("instance", "pod", "container", "job", "namespace", "device", "mountpoint");

  public String fingerprint(CanonicalAlert alert) {
    StringBuilder canonical = new StringBuilder("v").append(VERSION);
    canonical.append('|').append(normalize(alert.source()));
    canonical.append('|').append(normalize(alert.alertName()));
    canonical.append('|').append(normalize(alert.service()));
    canonical.append('|').append(normalize(alert.environment()));

    if (alert.labels() != null) {
      Map<String, String> identityLabels = new TreeMap<>();
      for (Map.Entry<String, String> entry : alert.labels().entrySet()) {
        String key = normalize(entry.getKey());
        if (IDENTITY_LABEL_KEYS.contains(key)) {
          identityLabels.put(key, normalize(entry.getValue()));
        }
      }
      for (Map.Entry<String, String> entry : identityLabels.entrySet()) {
        canonical.append('|').append(entry.getKey()).append('=').append(entry.getValue());
      }
    }

    return sha256Hex(canonical.toString());
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private String sha256Hex(String input) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 must always be available", e);
    }
  }
}
