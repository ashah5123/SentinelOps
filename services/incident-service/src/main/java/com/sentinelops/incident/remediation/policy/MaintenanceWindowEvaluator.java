package com.sentinelops.incident.remediation.policy;

import com.sentinelops.incident.remediation.RemediationProperties;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Evaluates the fixed weekly maintenance window used by {@link MaintenanceWindowPolicyRule}. */
@Component
public class MaintenanceWindowEvaluator {

  private final RemediationProperties.MaintenanceWindow config;

  public MaintenanceWindowEvaluator(RemediationProperties properties) {
    this.config = properties.maintenanceWindow();
  }

  public boolean isActive(Instant at) {
    Set<String> days = Set.copyOf(config.daysOfWeek());
    ZonedDateTime zoned = at.atZone(ZoneId.of(config.zoneId()));
    if (!days.contains(zoned.getDayOfWeek().name())) {
      return false;
    }
    int hour = zoned.getHour();
    return hour >= config.startHour() && hour < config.endHour();
  }

  public boolean isActiveNow() {
    return isActive(Instant.now());
  }
}
