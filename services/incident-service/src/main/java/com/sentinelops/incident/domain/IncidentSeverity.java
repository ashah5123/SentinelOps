package com.sentinelops.incident.domain;

/**
 * Severity of an incident, in descending order of impact.
 *
 * <ul>
 *   <li>{@link #SEV1} — critical, widespread customer impact or full outage.
 *   <li>{@link #SEV2} — major functionality degraded or impaired for a subset of users.
 *   <li>{@link #SEV3} — minor degradation with a viable workaround.
 *   <li>{@link #SEV4} — cosmetic or informational; no meaningful customer impact.
 * </ul>
 */
public enum IncidentSeverity {
  SEV1,
  SEV2,
  SEV3,
  SEV4
}
