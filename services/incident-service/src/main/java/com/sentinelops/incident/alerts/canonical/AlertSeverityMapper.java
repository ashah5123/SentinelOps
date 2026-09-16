package com.sentinelops.incident.alerts.canonical;

import java.util.Locale;
import java.util.Map;

/**
 * Maps a source system's own severity vocabulary onto SentinelOps' {@code SEV1..SEV4} scale. Shared
 * by every connector so severity mapping is defined in exactly one place. An unrecognized value
 * maps to {@code null} — routing's default route (never this mapper) decides what an unmapped
 * severity means, so this class never guesses.
 */
public final class AlertSeverityMapper {

  private static final Map<String, String> KNOWN =
      Map.ofEntries(
          Map.entry("critical", "SEV1"),
          Map.entry("page", "SEV1"),
          Map.entry("emergency", "SEV1"),
          Map.entry("major", "SEV2"),
          Map.entry("error", "SEV2"),
          Map.entry("warning", "SEV3"),
          Map.entry("minor", "SEV3"),
          Map.entry("info", "SEV4"),
          Map.entry("informational", "SEV4"));

  private AlertSeverityMapper() {}

  public static String map(String sourceSeverity) {
    if (sourceSeverity == null || sourceSeverity.isBlank()) {
      return null;
    }
    return KNOWN.get(sourceSeverity.trim().toLowerCase(Locale.ROOT));
  }
}
