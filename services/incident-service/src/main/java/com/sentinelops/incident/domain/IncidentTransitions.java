package com.sentinelops.incident.domain;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Defines and enforces the allowed incident lifecycle transitions.
 *
 * <p>Clients never set {@link IncidentStatus} directly; every change goes through {@link
 * #requireAllowed(IncidentStatus, IncidentStatus)}, which rejects any transition not present in the
 * allow-list below.
 */
public final class IncidentTransitions {

  private static final Map<IncidentStatus, Set<IncidentStatus>> ALLOWED = buildAllowedMap();

  private IncidentTransitions() {}

  private static Map<IncidentStatus, Set<IncidentStatus>> buildAllowedMap() {
    Map<IncidentStatus, Set<IncidentStatus>> map = new EnumMap<>(IncidentStatus.class);
    map.put(
        IncidentStatus.DETECTED, EnumSet.of(IncidentStatus.INVESTIGATING, IncidentStatus.FAILED));
    map.put(
        IncidentStatus.INVESTIGATING,
        EnumSet.of(
            IncidentStatus.AWAITING_APPROVAL, IncidentStatus.MITIGATING, IncidentStatus.FAILED));
    map.put(
        IncidentStatus.AWAITING_APPROVAL,
        EnumSet.of(IncidentStatus.MITIGATING, IncidentStatus.INVESTIGATING, IncidentStatus.FAILED));
    map.put(IncidentStatus.MITIGATING, EnumSet.of(IncidentStatus.RESOLVED, IncidentStatus.FAILED));
    map.put(IncidentStatus.RESOLVED, EnumSet.noneOf(IncidentStatus.class));
    map.put(IncidentStatus.FAILED, EnumSet.noneOf(IncidentStatus.class));
    return Map.copyOf(map);
  }

  public static boolean isAllowed(IncidentStatus from, IncidentStatus to) {
    return ALLOWED.getOrDefault(from, Set.of()).contains(to);
  }

  public static void requireAllowed(IncidentStatus from, IncidentStatus to) {
    if (!isAllowed(from, to)) {
      throw new IllegalIncidentTransitionException(from, to);
    }
  }

  public static Set<IncidentStatus> allowedFrom(IncidentStatus from) {
    return ALLOWED.getOrDefault(from, Set.of());
  }
}
